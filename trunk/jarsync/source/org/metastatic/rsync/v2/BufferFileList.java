/* vim:set softtabstop=3 shiftwidth=3 tabstop=3 expandtab tw=72:
   $Id$

   SendFiles: create and send a list of files.
   Copyright (C) 2003  Casey Marshall <rsdio@metastatic.org>

   This file is a part of Jarsync.

   Jarsync is free software; you can redistribute it and/or modify it
   under the terms of the GNU General Public License as published by the
   Free Software Foundation; either version 2 of the License, or (at
   your option) any later version.

   Jarsync is distributed in the hope that it will be useful, but
   WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with Jarsync; if not, write to the

      Free Software Foundation, Inc.,
      59 Temple Place, Suite 330,
      Boston, MA  02111-1307
      USA  */

package org.metastatic.rsync.v2;

import java.io.FileInputStream ;
import java.io.IOException;

import java.nio.ByteBuffer;

import java.security.MessageDigest;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javaunix.UnixSystem;
import javaunix.io.UnixFile;

import org.apache.log4j.Logger;

public final class BufferFileList implements BufferTool, Constants {

   // Constants and fields.
   // -----------------------------------------------------------------------

   private final Options options;
   private final String path;
   private final List argv;
   private final Logger logger;
   private final int remoteVersion;

   private final List files;
   private final Map uids;
   private final Map gids;

   private Iterator id_iterator;
   private int index;
   private FileInfo lastfile;
   private String lastname = "";
   private int state;
   private int io_error = 0;

   private DuplexByteBuffer outBuffer;
   private ByteBuffer inBuffer;

   // Constructors.
   // -----------------------------------------------------------------------

   public BufferFileList(Options options, String path, List argv,
                         int remoteVersion, Logger logger, int state)
   {
      this.options = options;
      this.path = path;
      this.argv = argv;
      this.remoteVersion = remoteVersion;
      this.logger = logger;
      this.state = state;
      files = new LinkedList();
      uids = new HashMap();
      gids = new HashMap();
      index = 0;
      lastfile = new FileInfo();
      lastfile.basename = "";
      lastfile.dirname = "";
      lastfile.mode = 0;
      lastfile.rdev = -1;
      lastfile.uid = -1;
      lastfile.gid = -1;
      lastfile.modtime = 0;
   }

   // Instance methods.
   // -----------------------------------------------------------------------

   public boolean updateInput() throws Exception {
      switch (state & INPUT_MASK) {
         case FLIST_RECEIVE_FILES:
         case FLIST_RECEIVE_UIDS:
         case FLIST_RECEIVE_GIDS:
         case FLIST_RECEIVE_DONE:
            if (remoteVersion >= 17)
               io_error = inBuffer.getInt();
      }
      return false;
   }

   public boolean updateOutput() throws Exception {
      switch (state & OUTPUT_MASK) {
         case FLIST_SEND_FILES:
            sendNextFile();
            return true;
         case FLIST_SEND_UIDS:
            sendUidList();
            return true;
         case FLIST_SEND_GIDS:
            sendGidList();
            return true;
         case FLIST_SEND_DONE:
            outBuffer.put((byte) 0);
            if (remoteVersion >= 17)
               outBuffer.putInt(io_error);
            return false;
      }
      return false;
   }

   public void setBuffers(DuplexByteBuffer out, ByteBuffer in) {
      outBuffer = out;
      inBuffer = in;
   }

   public List getFileList() {
      return files;
   }

   public Map getUidList() {
      return uids;
   }

   public Map getGidList() {
      return gids;
   }

   // Own methods.
   // -----------------------------------------------------------------------

   private void sendNextFile() throws Exception {
      UnixFile f = new UnixFile(path, (String) argv.get(index));
      if (f.isDirectory() && !options.recurse) {
         logger.info("skipping directory " + f.getName());
         if (++index < argv.size()) {
            if (!options.numeric_ids) {
               if (options.preserve_uid) {
                  id_iterator = uids.keySet().iterator();
                  state = FLIST_SEND_UIDS;
               } else if (options.preserve_gid) {
                  id_iterator = gids.keySet().iterator();
                  state = FLIST_SEND_GIDS;
               } else {
                  state = FLIST_SEND_DONE;
               }
            } else {
               state = FLIST_SEND_DONE;
            }
         }
         return;
      }

      FileInfo file = null;
      try {
         file = new FileInfo(f);
      } catch (IOException ioe) {
         io_error = 1;
         file = new FileInfo();
         file.dirname = f.getParent();
         file.basename = f.getName();
      }
      if (file.dirname.startsWith(path)) {
         file.dirname = file.dirname.substring(path.length());
      }
      if (options.always_checksum) {
         if (file.S_ISREG()) {
            try {
               MessageDigest md4 = MessageDigest.getInstance("BrokenMD4");
               FileInputStream fin = new FileInputStream(f);
               byte[] buf = new byte[4096];
               int len = 0;
               while ((len = fin.read(buf)) != 0)
                  md4.update(buf, 0, len);
               file.sum = md4.digest();
            } catch (Exception e) {
               io_error = 1;
               file.sum = new byte[SUM_LENGTH];
            }
         } else {
            file.sum = new byte[SUM_LENGTH];
         }
      }
      files.add(file);

      file.flags = 0;
      if (file.mode == lastfile.mode)
         file.flags |= SAME_MODE;
      if (file.rdev == lastfile.rdev)
         file.flags |= SAME_RDEV;
      if (file.uid == lastfile.uid)
         file.flags |= SAME_UID;
      if (file.gid == lastfile.gid)
         file.flags |= SAME_GID;
      if (file.modtime == lastfile.modtime)
         file.flags |= SAME_TIME;

      logger.debug("sending file entry: " + file);

      String fname = file.dirname;
      if (fname.startsWith(UnixFile.separator))
         fname = fname.substring(1);
      if (fname.length() > 0)
         fname += UnixFile.separator;
      fname += file.basename;
      int l1;
      for (l1 = 0; l1 < fname.length() && l1 < lastname.length() &&
         fname.charAt(l1) == lastname.charAt(l1) && l1 < 255;
         l1++);
      int l2 = fname.length() - l1;
      lastname = fname;

      if (l1 > 0)
         file.flags |= SAME_NAME;
      if (l2 > 255)
         file.flags |= LONG_NAME;

      if (file.flags == 0 && !f.isDirectory())
         file.flags = FLAG_DELETE;
      if (file.flags == 0)
         file.flags = LONG_NAME;

      outBuffer.put((byte) file.flags);
      if ((file.flags & SAME_NAME) != 0)
         outBuffer.put((byte) l1);
      if ((file.flags & LONG_NAME) != 0)
         outBuffer.putString(
            fname.substring(fname.length() - l2)
                 .replace(UnixFile.separatorChar, '/'));
      else
         outBuffer.putShortString(
            fname.substring(fname.length() - l2)
                 .replace(UnixFile.separatorChar, '/'));

      outBuffer.putLong(file.length);
      if ((file.flags & SAME_TIME) == 0)
         outBuffer.putInt((int) file.modtime);
      if ((file.flags & SAME_MODE) == 0)
         outBuffer.putInt(toWireMode(file.mode));
      if (options.preserve_uid && (file.flags & SAME_UID) == 0) {
         try {
            Integer uid = new Integer(file.uid);
            String user = UnixSystem.getPasswordByUid(file.uid).pw_name;
            if (!uids.containsKey(uid))
               uids.put(uid, user);
         } catch (IOException ioe) {
            io_error = 1;
         }
         outBuffer.putInt(file.uid);
      }
      if (options.preserve_gid && (file.flags & SAME_GID) == 0) {
         try {
            Integer gid = new Integer(file.gid);
            String group = UnixSystem.getGroupByGid(file.gid).gr_name;
            if (!gids.containsKey(gid))
               gids.put(gid, group);
         } catch (IOException ioe) {
            io_error = 1;
         }
         outBuffer.putInt(file.gid);
      }
      if (options.preserve_links && file.S_ISLNK()) {
         outBuffer.putString(file.link);
      }

      if (options.always_checksum) {
         if (remoteVersion < 21)
            outBuffer.put(file.sum, 0, 2);
         else
            outBuffer.put(file.sum);
      }

      if (f.isDirectory() && options.recurse)
         expandDirectory(f);

      if (++index == argv.size()) {
         if (!options.numeric_ids) {
            if (options.preserve_uid) {
               id_iterator = uids.keySet().iterator();
               state = FLIST_SEND_UIDS;
            } else if (options.preserve_gid) {
               id_iterator = gids.keySet().iterator();
               state = FLIST_SEND_GIDS;
            } else {
               state = FLIST_SEND_DONE;
            }
         } else {
            state = FLIST_SEND_DONE;
         }
      }
   }

   private void sendUidList() throws Exception {
      if (!id_iterator.hasNext()) {
         outBuffer.putInt(0);
         if (options.preserve_gid) {
            id_iterator = gids.keySet().iterator();
            state = FLIST_SEND_GIDS;
         } else {
            state = FLIST_SEND_DONE;
         }
         return;
      }
      Integer uid = (Integer) id_iterator.next();
      outBuffer.putInt(uid.intValue());
      outBuffer.putShortString((String) uids.get(uid));
   }

   private void sendGidList() throws Exception {
      if (!id_iterator.hasNext()) {
         outBuffer.putInt(0);
         state = FLIST_SEND_DONE;
         return;
      }
      Integer gid = (Integer) id_iterator.next();
      outBuffer.putInt(gid.intValue());
      outBuffer.putShortString((String) gids.get(gid));
   }

   private void expandDirectory(UnixFile dir) {
      String dirname = dir.getPath();
      logger.debug("dirname=" + dirname);
      if (dirname.startsWith(path))
         dirname = dirname.substring(path.length());
      if (dirname.startsWith(UnixFile.separator))
         dirname = dirname.substring(1);
      if (dirname.startsWith("."))
         dirname = dirname.substring(1);
      logger.debug("dirname=" + dirname);
      String[] list = dir.list(new Glob(options.exclude, false,
         path + UnixFile.separator + argv.get(0)));
      if (list != null) {
         for (int i = 0; i < list.length; i++) {
            argv.add(index+1, dirname + UnixFile.separator + list[i]);
         }
      }
   }

   private static int toWireMode(int mode) {
      if (FileInfo.S_ISLNK(mode))
         return (mode & ~(_S_IFMT)) | 0120000;
      return mode;
   }
}

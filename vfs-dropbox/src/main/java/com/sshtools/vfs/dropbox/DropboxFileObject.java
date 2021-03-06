/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sshtools.vfs.dropbox;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileNotFolderException;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.provider.AbstractFileName;
import org.apache.commons.vfs2.provider.AbstractFileObject;
import org.apache.commons.vfs2.provider.UriParser;
import org.apache.commons.vfs2.util.FileObjectUtils;
import org.apache.commons.vfs2.util.Messages;

import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.Metadata;

/**
 * A Dropbox file.
 *
 * @author <a href="http://commons.apache.org/vfs/team-list.html">Commons VFS
 *         team</a>
 */
public class DropboxFileObject extends AbstractFileObject<DropboxFileSystem> {
	private static final Map<String, Metadata> EMPTY_FTP_FILE_MAP = Collections
			.unmodifiableMap(new TreeMap<String, Metadata>());
	private final Log log = LogFactory.getLog(DropboxFileObject.class);
	private final DropboxFileSystem dbxFs;
	private final String relPath;
	// Cached info
	private Metadata fileInfo;
	private FileType fileType;
	private Map<String, Metadata> children;
	private boolean inRefresh;

	protected DropboxFileObject(final AbstractFileName name, final DropboxFileSystem fileSystem,
			final FileName rootName) throws FileSystemException {
		super(name, fileSystem);
		dbxFs = fileSystem;
		String relPath = UriParser.decode(rootName.getRelativeName(name));
		{
			this.relPath = relPath;
		}
	}

	/**
	 * Called by child file objects, to locate their ftp file info.
	 *
	 * @param name
	 *            the filename in its native form ie. without uri stuff (%nn)
	 * @param flush
	 *            recreate children cache
	 */
	private Metadata getChildFile(final String name, final boolean flush) throws IOException {
		/*
		 * If we should flush cached children, clear our children map unless
		 * we're in the middle of a refresh in which case we've just recently
		 * refreshed our children. No need to do it again when our children are
		 * refresh()ed, calling getChildFile() for themselves from within
		 * getInfo(). See getChildren().
		 */
		if (flush && !inRefresh) {
			children = null;
		}
		// List the children of this file
		doGetChildren();
		// VFS-210
		if (children == null) {
			return null;
		}
		// Look for the requested child
		return children.get(name);
	}

	/**
	 * Fetches the children of this file, if not already cached.
	 */
	private void doGetChildren() throws IOException {
		if (children != null) {
			return;
		}
		final DropboxClient client = dbxFs.getClient();
		try {
			final List<Metadata> tmpChildren = client.listFiles(getAbsPath());
			if (tmpChildren.isEmpty()) {
				children = EMPTY_FTP_FILE_MAP;
			} else {
				children = new TreeMap<String, Metadata>();
				// Remove '.' and '..' elements
				for (Metadata child : tmpChildren) {
					if (child == null) {
						if (log.isDebugEnabled()) {
							log.debug(Messages.getString("vfs.provider.dbx/invalid-directory-entry.debug",
									new Object[] { tmpChildren.indexOf(child), relPath }));
						}
						continue;
					}
					if (!".".equals(child.getName()) && !"..".equals(child.getName())) {
						children.put(child.getName(), child);
					}
				}
			}
		} finally {
			dbxFs.putClient(client);
		}
	}

	/**
	 * Attaches this file object to its file resource.
	 */
	@Override
	protected void doAttach() throws IOException {
		// Get the parent folder to find the info for this file
		// VFS-210 getInfo(false);
	}

	/**
	 * Fetches the info for this file.
	 */
	private void getInfo(boolean flush) throws IOException {
		final DropboxFileObject parent = (DropboxFileObject) FileObjectUtils.getAbstractFileObject(getParent());
		Metadata newFileInfo;
		if (parent != null) {
			newFileInfo = parent.getChildFile(UriParser.decode(getName().getBaseName()), flush);
			if (newFileInfo == null) {
				this.fileInfo = null;
				this.fileType = FileType.IMAGINARY;
			} else {
				this.fileInfo = newFileInfo;
				if (this.fileInfo instanceof FolderMetadata) {
					this.fileType = FileType.FOLDER;
				} else if (this.fileInfo instanceof FileMetadata) {
					this.fileType = FileType.FILE;
				}
			}
		} else {
			// Assume the root is a directory and exists
			this.fileInfo = new FolderMetadata("/", "__ROOT__");
			this.fileType = FileType.FOLDER;
		}
	}

	/**
	 * @throws FileSystemException
	 *             if an error occurs.
	 */
	@Override
	public void refresh() throws FileSystemException {
		if (!inRefresh) {
			try {
				inRefresh = true;
				super.refresh();
				synchronized (getFileSystem()) {
					this.fileInfo = null;
				}
				/*
				 * VFS-210 try { // this will tell the parent to recreate its
				 * children collection getInfo(true); } catch (IOException e) {
				 * throw new FileSystemException(e); }
				 */
			} finally {
				inRefresh = false;
			}
		}
	}

	/**
	 * Detaches this file object from its file resource.
	 */
	@Override
	protected void doDetach() {
		synchronized (getFileSystem()) {
			this.fileInfo = null;
			this.fileType = null;
			children = null;
		}
	}

	/**
	 * Called when the children of this file change.
	 */
	@Override
	protected void onChildrenChanged(FileName child, FileType newType) {
		if (children != null && newType.equals(FileType.IMAGINARY)) {
			try {
				children.remove(UriParser.decode(child.getBaseName()));
			} catch (FileSystemException e) {
				throw new RuntimeException(e.getMessage());
			}
		} else {
			// if child was added we have to rescan the children
			// TODO - get rid of this
			children = null;
		}
	}

	/**
	 * Called when the type or content of this file changes.
	 */
	@Override
	protected void onChange() throws IOException {
		children = null;
		if (getType().equals(FileType.IMAGINARY)) {
			// file is deleted, avoid server lookup
			synchronized (getFileSystem()) {
				this.fileInfo = null;
			}
			return;
		}
		getInfo(true);
	}

	/**
	 * Determines the type of the file, returns null if the file does not exist.
	 */
	@Override
	protected FileType doGetType() throws Exception {
		// VFS-210
		synchronized (getFileSystem()) {
			if (this.fileType == null) {
				getInfo(false);
			}
			if (this.fileType != null) {
				return fileType;
			}
		}
		throw new FileSystemException("vfs.provider.dbx/get-type.error", getName());
	}

	@Override
	protected FileObject[] doListChildrenResolved() throws Exception {
		doGetChildren();
		if (children == null)
			return null;
		FileObject[] result = new FileObject[children.size()];
		int i = 0;
		for (Metadata entry : children.values()) {
			result[i] = dbxFs.resolveFile(UriParser.encode(entry.getPathDisplay()));
			i++;
		}
		return result;
	}

	/**
	 * Returns the file's list of children.
	 *
	 * @return The list of children
	 * @throws FileSystemException
	 *             If there was a problem listing children
	 * @see AbstractFileObject#getChildren()
	 * @since 2.0
	 */
	@Override
	public FileObject[] getChildren() throws FileSystemException {
		try {
			if (doGetType() != FileType.FOLDER) {
				throw new FileNotFolderException(getName());
			}
		} catch (Exception ex) {
			throw new FileNotFolderException(getName(), ex);
		}
		try {
			/*
			 * Wrap our parent implementation, noting that we're refreshing so
			 * that we don't refresh() ourselves and each of our parents for
			 * each children. Note that refresh() will list children. Meaning,
			 * if if this file has C children, P parents, there will be (C * P)
			 * listings made with (C * (P + 1)) refreshes, when there should
			 * really only be 1 listing and C refreshes.
			 */
			this.inRefresh = true;
			return super.getChildren();
		} finally {
			this.inRefresh = false;
		}
	}

	/**
	 * Lists the children of the file.
	 */
	@Override
	protected String[] doListChildren() throws Exception {
		// List the children of this file
		doGetChildren();
		// VFS-210
		if (children == null) {
			return null;
		}
		// TODO - get rid of this children stuff
		final String[] childNames = new String[children.size()];
		int childNum = -1;
		Iterator<Metadata> iterChildren = children.values().iterator();
		while (iterChildren.hasNext()) {
			childNum++;
			final Metadata child = iterChildren.next();
			childNames[childNum] = child.getName();
		}
		return UriParser.encode(childNames);
	}

	/**
	 * Deletes the file.
	 */
	@Override
	protected void doDelete() throws Exception {
		synchronized (getFileSystem()) {
			final boolean ok;
			final DropboxClient ftpClient = dbxFs.getClient();
			try {
				if (this.fileInfo instanceof FolderMetadata) {
					ok = ftpClient.removeDirectory(getAbsPath());
				} else {
					ok = ftpClient.deleteFile(getAbsPath());
				}
			} finally {
				dbxFs.putClient(ftpClient);
			}
			if (!ok) {
				throw new FileSystemException("vfs.provider.ftp/delete-file.error", getName());
			}
			this.fileInfo = null;
			this.fileType = FileType.IMAGINARY;
			children = EMPTY_FTP_FILE_MAP;
		}
	}

	/**
	 * Renames the file
	 */
	@Override
	protected void doRename(FileObject newfile) throws Exception {
		synchronized (getFileSystem()) {
			final boolean ok;
			final DropboxClient ftpClient = dbxFs.getClient();
			try {
				String oldName = getName().getPath();
				String newName = newfile.getName().getPath();
				ok = ftpClient.rename(oldName, newName);
			} finally {
				dbxFs.putClient(ftpClient);
			}
			if (!ok) {
				throw new FileSystemException("vfs.provider.ftp/rename-file.error",
						new Object[] { getName().toString(), newfile });
			}
			this.fileInfo = null;
			this.fileType = FileType.IMAGINARY;
			children = EMPTY_FTP_FILE_MAP;
		}
	}

	/**
	 * Creates this file as a folder.
	 */
	@Override
	protected void doCreateFolder() throws Exception {
		final boolean ok;
		final DropboxClient client = dbxFs.getClient();
		try {
			ok = client.makeDirectory(getAbsPath());
		} finally {
			dbxFs.putClient(client);
		}
		if (!ok) {
			throw new FileSystemException("vfs.provider.ftp/create-folder.error", getName());
		}
	}

	/**
	 * Returns the size of the file content (in bytes).
	 */
	@Override
	protected long doGetContentSize() throws Exception {
		synchronized (getFileSystem()) {
			if (this.fileInfo instanceof FileMetadata)
				return ((FileMetadata) this.fileInfo).getSize();
		}
		return -1;
	}

	/**
	 * get the last modified time on an ftp file
	 *
	 * @see org.apache.commons.vfs2.provider.AbstractFileObject#doGetLastModifiedTime()
	 */
	@Override
	protected long doGetLastModifiedTime() throws Exception {
		synchronized (getFileSystem()) {
			if (this.fileInfo instanceof FileMetadata)
				return ((FileMetadata) this.fileInfo).getServerModified().getTime();
			return 0;
		}
	}

	/**
	 * Creates an input stream to read the file content from.
	 */
	@Override
	protected InputStream doGetInputStream() throws Exception {
		final DropboxClient client = dbxFs.getClient();
		try {
			InputStream instr = client.retrieveFileStream(getAbsPath());
			// VFS-210
			if (instr == null) {
				throw new FileNotFoundException(getName().toString());
			}
			instr = new FilterInputStream(instr) {
				@Override
				public void close() throws IOException {
					try {
						super.close();
					} finally {
						dbxFs.putClient(client);
					}
				}
			};
			return instr;
		} catch (Exception e) {
			dbxFs.putClient(client);
			throw e;
		}
	}

	/**
	 * Creates an output stream to write the file content to.
	 */
	@Override
	protected OutputStream doGetOutputStream(boolean bAppend) throws Exception {
		final DropboxClient client = dbxFs.getClient();
		try {
			OutputStream out = null;
			if (bAppend) {
				// out = client.appendFileStream(relPath);
			} else {
				final OutputStream storeOut = client.storeFileStream(getAbsPath());
				out = new FilterOutputStream(storeOut) {
					@Override
					public void write(byte[] b, int off, int len) throws IOException {
						storeOut.write(b, off, len);
					}

					@Override
					public void close() throws IOException {
						try {
							super.close();
						} finally {
							dbxFs.putClient(client);
						}
					}
				};
			}
			if (out == null) {
				throw new FileSystemException("vfs.provider.dbx/output-error.debug", new Object[] { this.getName(), });
			}
			return out;
		} catch (Exception e) {
			dbxFs.putClient(client);
			throw e;
		}
	}

	String getRelPath() {
		return relPath;
	}

	String getAbsPath() {
		return super.getName().getPath();
	}
}

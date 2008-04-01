/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.adapter.ftp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import org.springframework.integration.adapter.PollableSource;
import org.springframework.integration.adapter.PollingSourceAdapter;
import org.springframework.integration.adapter.file.ByteArrayFileMapper;
import org.springframework.integration.adapter.file.TextFileMapper;
import org.springframework.integration.message.MessageHandlingException;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * A source adapter for receiving files via FTP.
 * 
 * @author Marius Bogoevici
 * @author Mark Fisher
 */
public class FtpSourceAdapter extends PollingSourceAdapter<File> implements PollableSource<File> {

	private final static String DEFAULT_HOST = "localhost";

	private final static int DEFAULT_PORT = 21;

	private final static String DEFAULT_REMOTE_WORKING_DIRECTORY = "/";


	private final Log logger = LogFactory.getLog(this.getClass());

	private volatile String username;

	private volatile String password;

	private volatile String host = DEFAULT_HOST;

	private volatile int port = DEFAULT_PORT;

	private volatile String remoteWorkingDirectory = DEFAULT_REMOTE_WORKING_DIRECTORY;

	private volatile File localWorkingDirectory;

	private volatile boolean textBased = true;

	private final DirectoryContentManager directoryContentManager = new DirectoryContentManager();

	private final FTPClient client = new FTPClient();


	public void setHost(String host) {
		this.host = host;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setRemoteWorkingDirectory(String remoteWorkingDirectory) {
		Assert.hasText(remoteWorkingDirectory, "'remoteWorkingDirectory' is required");
		this.remoteWorkingDirectory = remoteWorkingDirectory;
	}

	public void setLocalWorkingDirectory(File localWorkingDirectory) {
		Assert.notNull(localWorkingDirectory, "'localWorkingDirectory' must not be null");
		this.localWorkingDirectory = localWorkingDirectory;
	}

	public boolean isTextBased() {
		return textBased;
	}

	public void setTextBased(boolean textBased) {
		this.textBased = textBased;
	}

	@Override
	protected void initialize() {
		this.setSource(this);
		if (this.isTextBased()) {
			this.setMessageMapper(new TextFileMapper(this.localWorkingDirectory));
		}
		else {
			this.setMessageMapper(new ByteArrayFileMapper(this.localWorkingDirectory));
		}
	}

	@Override
	protected void onSend(File file) {
		this.directoryContentManager.fileProcessed(file.getName());
	}


	public final Collection<File> poll(int limit) {
		try {
			LinkedList<File> localFileList = new LinkedList<File>();
			this.client.connect(this.host, this.port);
			if (!StringUtils.hasText(this.username)) {
				throw new MessageHandlingException("username is required");
			}
			if (!this.client.login(this.username, this.password)) {
				throw new MessageHandlingException("Login failed. Please check the username and password.");				
			}
			if (logger.isDebugEnabled()) {
				logger.debug("login successful");
			}
			this.client.setFileType(FTP.IMAGE_FILE_TYPE);
			if (!this.remoteWorkingDirectory.equals(this.client.printWorkingDirectory())
					&& !this.client.changeWorkingDirectory(this.remoteWorkingDirectory)) {
					throw new MessageHandlingException("Could not change directory to '" +
							remoteWorkingDirectory + "'. Please check the path.");
			}
			if (logger.isDebugEnabled()) {
				logger.debug("working directory is: " + this.client.printWorkingDirectory());
			}
			FTPFile[] fileList = this.client.listFiles();
			HashMap<String, FileInfo> snapshot = new HashMap<String, FileInfo>();
			for (FTPFile ftpFile : fileList) {
				FileInfo fileInfo = new FileInfo(ftpFile.getName(), ftpFile.getTimestamp().getTimeInMillis(),
						ftpFile.getSize());
				snapshot.put(ftpFile.getName(), fileInfo);
			}
			this.directoryContentManager.processSnapshot(snapshot);
			for (String fileName : this.directoryContentManager.getBacklog().keySet()) {
				File file = new File(this.localWorkingDirectory, fileName);
				if (file.exists()) {
					file.delete();
				}
				FileOutputStream fileOutputStream = new FileOutputStream(file);
				this.client.retrieveFile(fileName, fileOutputStream);
				fileOutputStream.close();
				localFileList.add(file);
				if (limit >= localFileList.size()) {
					break;
				}
			}
			return localFileList;
		}
		catch (Exception e) {
			try {
				if (this.client.isConnected()) {
					this.client.disconnect();
				}
			}
			catch (IOException ioe) {
				throw new MessageHandlingException("Error when disconnecting from ftp.", ioe);
			}
			throw new MessageHandlingException("Error while polling for messages.", e);
		}
	}

}

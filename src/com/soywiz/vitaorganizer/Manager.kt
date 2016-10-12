package com.soywiz.vitaorganizer

import it.sauronsoftware.ftp4j.FTPClient
import it.sauronsoftware.ftp4j.FTPDataTransferListener
import it.sauronsoftware.ftp4j.FTPException
import it.sauronsoftware.ftp4j.FTPFile
import it.sauronsoftware.ftp4j.FTPReply
import java.io.*
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.*
import java.util.zip.DeflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.swing.JOptionPane

object Manager {
	var taskrunning: Boolean = false

	fun isTaskRunning(): Boolean {
		return taskrunning
	}

	fun setTaskRunning(taskrunning: Boolean) {
		this.taskrunning = taskrunning
	}
}

object ConnectionMgr {
	var connected: Boolean = false
	var authenticated: Boolean = false
	var transferingdata: Boolean = false
	val ftp: FTPClient = FTPClient()
	val lock: Any = Any()

	init {
		ftp.setType(FTPClient.TYPE_BINARY)
		ftp.setPassive(true)
		setFtpTimeouts()
	}

	fun setFtpTimeouts() {
		ftp.connector.setCloseTimeout(20)
		ftp.connector.setReadTimeout(240) // PROM could take a lot of time!
		ftp.connector.setConnectionTimeout(120)
		ftp.connector.setCloseTimeout(5)
	}

	 fun setConnectionStatus(connected: Boolean, authenticated: Boolean) {
		this.connected = connected
		this.authenticated = authenticated
	}

	fun isTransferingData(): Boolean {
		return transferingdata
	}

	fun setTransferingData(transfering: Boolean) {
		this.transferingdata = transfering
	}

	fun checkAddress(hostname: String, port: Int = 1337, timeout: Int = 200): Boolean {
		try {
			val sock = Socket()
			sock.connect(InetSocketAddress(hostname, port), timeout)
			sock.close()
			return true
		} catch (e: Throwable) {
			return false
		}
	}

	fun discoverIp(port: Int = 1337): List<String> {
		val ips = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
		val ips2 = ips.map { it.hostAddress }.filter { it.startsWith("192.") }
		val ipEnd = Regex("\\.(\\d+)$")
		val availableIps = arrayListOf<String>()
		var rest = 256
		for (baseIp in ips2) {
			for (n in 0 until 256) {
				Thread {
					val ip = baseIp.replace(ipEnd, "\\.$n")
					if (checkAddress(ip, port)) {
						availableIps += ip
					}
					rest--
				}.start()
			}
		}
		while (rest > 0) {
			//println(rest)
			Thread.sleep(20L)
		}
		//println(availableIps)
		return availableIps
	}

	fun isConnected(): Boolean {
		//maybe send a NOOP to see if it throws an exception,
		//because ftp.isConnected()
		//is not reliable. The server could terminate the
		//connection without these functions knowing about it
		//immediatelly which causes us problems
		return ftp.isConnected() && this.connected;
	}

	fun isAuthenticated(): Boolean {
		return ftp.isAuthenticated() && this.authenticated;
	}

	fun isDisconnected() : Boolean {
		//again, not reliable
		return !ftp.isConnected() && !this.connected;
	}

	fun connectToFtp(hostname: String, port: Int = 1337, username: String = "", password: String = ""): Boolean {

		if(hostname.isEmpty())
			return false

		if(isConnected() && isAuthenticated()) {
			return true
		}

		try {
			println("Connecting to $hostname:$port...")
			val response: Array<String> = ftp.connect(hostname, port)
			val msg = response.get(0)
			println("Connected, welcome msg: $msg")
		}
		catch(e: IOException) {
			println("IOException: disconnecting")
			ftp.disconnect(false)
			setConnectionStatus(false, false)
			return false
		}
		catch(e: FTPException) {
			println("FTPException: disconnecting")
			ftp.disconnect(false)
			setConnectionStatus(false, false)
			return false
		}
		catch(e: IllegalStateException) {
			println("IllegalStateException: already connected")
		}

		if(!ftp.isConnected()) {
			println("Connection failed for unknown reason")
			ftp.abruptlyCloseCommunication()
			setConnectionStatus(false, false)
			return false
		}

		setConnectionStatus(true, false)

		try {
			println("Authenticating with username=\'$username\' and password=\'$password\'...")
			ftp.login(username, password)
		}
		catch(e: IOException) {
			println("IOException: I/O Error, disconnecting")
			ftp.disconnect(true)
			setConnectionStatus(false, false)
			return false
		}
		catch(e: FTPException) {
			println("FTPException: Login failed, disconnecting with QUIT message")
			ftp.disconnect(true)
			setConnectionStatus(false, false)
			return false
		}

		if(!ftp.isAuthenticated) {
			println("Login failed, disconnecting with QUIT message")
			ftp.disconnect(true)
			setConnectionStatus(false, false)
			return false
		}

		println("Authenticated")
		setConnectionStatus(true, true)
		ftp.setAutoNoopTimeout(30000)
		return true
	}

	fun disconnectFromFtp(): Boolean {
		try {
			if(this.isConnected()) {
				if(this.isAuthenticated()) {
					ftp.setAutoNoopTimeout(0)
					ftp.logout()
					ftp.disconnect(true)
					ftp.abortCurrentDataTransfer(true)
				}
				else {
					ftp.disconnect(true)
					ftp.abortCurrentDataTransfer(false)
				}
			}
		}
		catch(e: Throwable) {

		}
		finally {
			ftp.abortCurrentDataTransfer(false)
			ftp.disconnect(false);
			ftp.abruptlyCloseCommunication()
			setConnectionStatus(false, false)
			setTransferingData(false)
		 }

		return isDisconnected()
	}

	fun getFtpClient(): FTPClient {
		return ftp
	}

	fun deleteFile(remotePath: String) : Boolean {
		if(remotePath.isEmpty())
			return false;

		val tmp = if(remotePath.startsWith('/')) "" else "/" + remotePath
		if(tmp.endsWith('/')) {
			//is this a directory?
			return false
		}

		if(!connectToFtp(VitaOrganizerSettings.lastDeviceIp, VitaOrganizerSettings.lastDevicePort.toInt()))
			return false;

		try {
			getFtpClient().deleteFile(tmp)
		} catch (e: Throwable) {
			println("Can't delete file $tmp")
			return false;
		}
		return true;
	}

	 fun downloadDirectory(remotePath: String, localPath: String) : Boolean {

		if(remotePath.isEmpty() || localPath.isEmpty())
			return false

		if(!connectToFtp(VitaOrganizerSettings.lastDeviceIp, VitaOrganizerSettings.lastDevicePort.toInt()))
			return false

		 var _remotePath = remotePath.replace("//", "/")
		 var _localPath = localPath.replace("//", "/")

		val ftp = ConnectionMgr.getFtpClient();
		try {
			val localFile = File(localPath)
			if(!localFile.exists())
				localFile.mkdirs()

			for(file in ftp.list(remotePath)) {
				if(file.type == FTPFile.TYPE_DIRECTORY) {
					println("[D] $_remotePath/${file.name}/")
				   val ret = downloadDirectory("$_remotePath/${file.name}", "$_localPath/${file.name}");
				   if(!ret) {
					   println("Could not download subdirectory $_remotePath${file.name}")
					   return false
				   }
				}
				else if(file.type == FTPFile.TYPE_FILE) {
					val f: File = File("$_localPath/${file.name}")
					if(!IOMgr.pathExists(f.parentFile as File)) {
						val t: String = f.parent
						println("$t doesnt exsist!")
						return false
					}
					print("[F] $_remotePath/${file.name} -> ")
					ftp.download("$_remotePath/${file.name}", f, object : FTPDataTransferListener {
						override fun started() {
							print("started...")
						}

						override fun completed() {
							 print("completed!")
						}

						override fun aborted() {
							 print("aborted!")
						}
						override fun transferred(size: Int) {
						   // print(".")
						}

						override fun failed() {
							print("failed!")
						}
					})
					println("")
				}
			}
		}
		catch(e: Throwable) {
			e.printStackTrace()
			return false
		}
		return true
	}
}

object IOMgr {

	fun pathExists(path: String): Boolean {
		if(path.isNullOrEmpty())
			return false

		try {
			return File(path).exists()
		}
		catch(e: SecurityException) {
			println("IOMgr::pathExists(String) -> SecurityException")
			e.printStackTrace()
		}
		return false
	}

	fun pathExists(file: File): Boolean {

		try {
			return file.exists();
		}
		catch(e: SecurityException) {
			println("IOMgr::pathExists(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun isFile(path: String): Boolean {
		if(path.isNullOrEmpty())
			return false;

		try {
			val file: File = File(path);
			if(!pathExists(file))
				return false;

			return file.isFile();
		}
		catch(e: SecurityException) {
			println("IOMgr::isFile(String) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun isFile(file: File): Boolean {
		if(!pathExists(file))
				return false

		try {
			return file.isFile();
		}
		catch(e: SecurityException) {
			println("IOMgr::isFile(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun isDirectory(path: String): Boolean {
		 if(path.isNullOrEmpty())
			return false;

		try {
			val file = File(path);
			if(!pathExists(file))
				return false;

			return file.isDirectory;
		}
		catch(e: SecurityException) {
			println("IOMgr::isDirectory(String) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun isDirectory(file: File): Boolean {
		if(!pathExists(file))
				return false

		try {
			return file.isDirectory;
		}
		catch(e: SecurityException) {
			println("IOMgr::isDirectory(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun canRead(path: String, recursive: Boolean = false): Boolean {
		if(path.isNullOrEmpty())
			return false;

		try {
			val file = File(path);
			if(!pathExists(file))
				return false;

			if(recursive && isDirectory(file)) {
				//TODO: implement
			}

			return file.canRead();
		}
		catch(e: SecurityException) {
			println("IOMgr::canRead(String) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun canRead(file: File): Boolean {
		if(!pathExists(file))
				return false

		try {
			return file.canRead();
		}
		catch(e: SecurityException) {
			println("IOMgr::canRead(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun canWrite(path: String, recursive: Boolean = false): Boolean {
		if(path.isNullOrEmpty())
			return false;

		try {
			val file: File = File(path);
			if(!pathExists(file))
				return false;

			if(recursive && isDirectory(file)) {
				//TODO: implement
			}
			return file.canWrite();
		}
		catch(e: SecurityException) {
			println("IOMgr::canWrite(String) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun canWrite(file: File): Boolean {
		if(!pathExists(file))
			return false;

		try {
			return file.canWrite();
		}
		catch(e: SecurityException) {
			println("IOMgr::canWrite(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun canReadWrite(file: File): Boolean {
		if(!pathExists(file))
			return false;

		try {
			return file.canRead() && file.canWrite();
		}
		catch(e: SecurityException) {
			println("IOMgr::canWrite(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	 fun canDelete(path: String, recursive: Boolean = false): Boolean {
		if(path.isNullOrEmpty())
			return false;

		try {
			val file = File(path);
			if(!pathExists(file))
				return false;

			if(recursive && isDirectory(file)) {
				//TODO: implement
			}
			//TODO: use SecurityManager::check_delete
			return file.canRead() && file.canWrite();
		}
		catch(e: SecurityException) {
			println("IOMgr::canDelete(String) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun canDelete(file: File): Boolean {
		if(!pathExists(file))
				return false

		try {
			//TODO: use SecurityManager::check_delete
			return file.canRead() && file.canWrite();
		}
		catch(e: SecurityException) {
			println("IOMgr::canDelete(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun canExecute(path: String): Boolean {
		if(path.isNullOrEmpty())
			return false;

		try {
			val file = File(path);
			if(!pathExists(file))
				return false;

			return file.canExecute();
		}
		catch(e: SecurityException) {
			println("IOMgr::canExecute(String) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun canExecute(file: File): Boolean {
		if(!pathExists(file))
				return false

		try {
			return file.canExecute();
		}
		catch(e: SecurityException) {
			println("IOMgr::canExecute(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun delete(path: String) : Boolean {
		if(path.isNullOrEmpty())
			return false;

		try {
			val file = File(path);

			if(!canDelete(file))
				return false;

			if(isDirectory(file))
				return file.deleteRecursively();
			else if(isFile(file))
				return file.delete();
		}
		catch(e: IOException) {
			println("IOMgr::delete(String) -> IOException")
			e.printStackTrace();
		}
		catch(e: SecurityException) {
			println("IOMgr::delete(String) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun delete(file: File) : Boolean {
		if(!canDelete(file))
			return false;

		try {
			if(isDirectory(file))
				return file.deleteRecursively();
			else if(isFile(file))
				return file.delete();
		}
		 catch(e: IOException) {
			println("IOMgr::delete(File) -> IOException")
			e.printStackTrace();
		}
		catch(e: SecurityException) {
			println("IOMgr::delete(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun createAndCheckFile(path: String) : Boolean {
	   if(path.isNullOrEmpty())
			return false;

	   try {
			val file: File = File(path);
			if(file.createNewFile())
				if(canWrite(file))
					return true;
	   }
		catch(e: IOException) {
			println("IOMgr::createAndCheckFile(String) -> IOException")
			e.printStackTrace();
		}
		catch(e: SecurityException) {
			println("IOMgr::createAndCheckFile(String) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun createAndCheckFile(file: File) : Boolean {
	   try {
			if(file.createNewFile())
				if(canWrite(file))
					return true;
	   }
		catch(e: IOException) {
			println("IOMgr::createAndCheckFile(File) -> IOException")
			e.printStackTrace();
		}
		catch(e: SecurityException) {
			println("IOMgr::createAndCheckFile(File) -> SecurityException")
			e.printStackTrace();
		}
		return false;
	}

	fun listAllFiles(dir: File): MutableList<File> {
		val mutableList : MutableList<File> = arrayListOf()

		val filelist = dir.listFiles();
		for (en in filelist) {
			mutableList.add(en);
			if(en.isDirectory) {
				mutableList.addAll(listAllFiles(en))
			}
		}
		return mutableList;
	}
}

object ZipMgr {

	fun writeZipFile(dir: File, extension: String = ".zip"): Boolean {
		if(!IOMgr.isDirectory(dir))
			return false

		val fileList = IOMgr.listAllFiles(dir)
		if(fileList.isEmpty())
			return false

		try {
			val fos = FileOutputStream("${dir.parent}/${dir.name}$extension")
			val zos = ZipOutputStream(fos)
			zos.setMethod(java.util.zip.Deflater.DEFLATED)
			zos.setLevel(9)
			for (file in fileList) {
				if(!addFileFromDirectory(file, dir, zos)) {
					println("failed $file!")
					return false
				}
			}

			zos.close()
			fos.close()
			return true
		}
		catch (e: Throwable) {
			e.printStackTrace()
			return false
		}
	}

	fun addFileFromDirectory(file: File, dir: File, zos: ZipOutputStream): Boolean {
		if(!IOMgr.canRead(file)) {
			println("cannot read file")
			return false
		}
		if(!IOMgr.isDirectory(dir)) {
			println("given directory isnt one")
			return false
		}

		try {
			var filePath = file.getCanonicalPath().substring(dir.getCanonicalPath().length + 1,
				file.getCanonicalPath().length)
			filePath = filePath.replace("\\", "/")
			println("Writing $filePath to zip")

			//val deflated = DeflaterInputStream(fis)
			if(file.isDirectory) {
				zos.putNextEntry(ZipEntry(filePath + if(!filePath.endsWith('/')) "/" else ""))
				zos.closeEntry()
				return true
			}

			zos.putNextEntry(ZipEntry(filePath))

			val fis = FileInputStream(file)
			val bytes = ByteArray(1024)

			while(true) {
				val length = fis.read(bytes)
				if(length < 0)
					break;

				zos.write(bytes, 0, length)
			}

			zos.closeEntry();
			//deflated.close()
			fis.close()

			return true
		}
		catch(e: Throwable) {
			println("exception ZipMgr::addFileFromDirectory")
			e.printStackTrace()
			return false;
		}
	}
}
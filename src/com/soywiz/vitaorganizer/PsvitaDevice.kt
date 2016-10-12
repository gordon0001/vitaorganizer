package com.soywiz.vitaorganizer

import com.soywiz.util.MemoryStream2
import com.soywiz.vitaorganizer.ext.getResourceURL
import it.sauronsoftware.ftp4j.*
import java.io.*
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.*
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.swing.JOptionPane

object PsvitaDevice {
    private fun connectedFtp(): FTPClient {
		val ip = VitaOrganizerSettings.lastDeviceIp
		val port = try { VitaOrganizerSettings.lastDevicePort.toInt() } catch (t: Throwable) { 1337 }

        if(ConnectionMgr.connectToFtp(ip, port)) {
            return ConnectionMgr.getFtpClient();
        }
        else
            throw Exception("Could not connect to ftp");

		@Suppress("UNREACHABLE_CODE")
		return ConnectionMgr.getFtpClient();
    }

    fun getGameIds() = connectedFtp().list("/ux0:/app/").filter { i -> i.type == it.sauronsoftware.ftp4j.FTPFile.TYPE_DIRECTORY }.map { File(it.name).name }

    fun getGameFolder(id: String) = "/ux0:/app/${File(id).name}"

    fun downloadSmallFile(path: String, abortAfterNoOfBytes: Int = -1): ByteArray {
        var fileSize = 0L
		try {
			fileSize = connectedFtp().fileSize(path)
            if (fileSize == 0L) {
				//println("Getting remote file succeeded, but the file size for $path is 0 byte")
                return byteArrayOf()
            }
        } catch (e: Throwable) {
			//println("Could not get file size of remote path $path")
            return byteArrayOf()
        }

        //val file = File.createTempFile("vita", "download")

		val stream = ByteArrayOutputStream()
		var sizeDownloaded: Int = 0
        try {
            connectedFtp().download(path, stream, 0, when {
                abortAfterNoOfBytes < 1 -> null
                else -> object : FTPDataTransferListener {
					override fun started() = Unit

					override fun completed() = Unit

					override fun aborted() {
						println("received abort command for $path, throwing exceoption. downloaded size = $sizeDownloaded")
					}

					override fun transferred(size: Int) {
						sizeDownloaded += size
						if(size > abortAfterNoOfBytes) {
							println("Aborting filetransfer to get only a chunk of the file $path")
							connectedFtp().abortCurrentDataTransfer(false)
                        }
					}

					override fun failed() {
						println("$path failed")
					}
				}
            })
            return stream.toByteArray()
        } catch(e: Throwable) {
			if(abortAfterNoOfBytes > 0) {
				println("Got the wanted download abort. path = $path  size = $sizeDownloaded")
				if(sizeDownloaded >= abortAfterNoOfBytes) {
					val size = stream.size()
					println("OK. $path $size")
					return stream.toByteArray()
                }
				else
					println("bla $path")
            }
			else {
				println("ftpexception $path")
				e.printStackTrace()
            }
        } finally {
            //e.printStackTrace()
            //file.delete()
			//stream.reset()
        }
        return byteArrayOf()
    }

    fun getParamSfo(id: String): ByteArray = downloadSmallFile("${getGameFolder(id)}/sce_sys/param.sfo")
    fun getGameIcon(id: String): ByteArray {
        val result = downloadSmallFile("${getGameFolder(id)}/sce_sys/icon0.png")
        return result
    }
    fun downloadEbootBin(id: String): ByteArray = downloadSmallFile("${getGameFolder(id)}/eboot.bin")

    fun getParamSfoCached(id: String): ByteArray {
        val file = VitaOrganizerCache.entry(id).paramSfoFile
        if (!file.exists()) file.writeBytes(getParamSfo(id))
        return file.readBytes()
    }

    fun getGameIconCached(id: String): ByteArray {
        val file = VitaOrganizerCache.entry(id).icon0File
        if (!file.exists()) {
			var icon = getGameIcon(id)
			if(icon.isEmpty()) {
				println("Could not get icon for $id. returning defaulticon")
				icon = getResourceURL("com/soywiz/vitaorganizer/icon.png").readBytes()
				if(icon.isEmpty()) {
					println("also could not get defaulticom for $id")
                }
			}
			else {
				//check for png magic 89 50 4E 47, 89 P N G
				if (icon[1].toChar() != 'P' || icon[2].toChar() != 'N' || icon[3].toChar() != 'G') {
					println("icon from $id is not a PNG file or is encrypted. replacing with default icon")
					icon = getResourceURL("com/soywiz/vitaorganizer/icon.png").readBytes()
				}
			}

			file.writeBytes(icon)
			return icon
		}
        return file.readBytes()
    }

    fun getFolderSize(path: String, folderSizeCache: HashMap<String, Long> = hashMapOf<String, Long>()): Long {
        return folderSizeCache.getOrPut(path) {
            var out = 0L
            val ftp = connectedFtp()
            try {
                for (file in ftp.list(path)) {
                    //println("$path/${file.name}: ${file.size}")
                    if (file.type == FTPFile.TYPE_DIRECTORY) {
                        out += getFolderSize("$path/${file.name}", folderSizeCache)
                    } else {
                        out += file.size
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            out
        }
    }

    fun getGameSize(id: String, folderSizeCache: HashMap<String, Long> = hashMapOf<String, Long>()): Long {
        return getFolderSize(getGameFolder(id), folderSizeCache)
    }

    class Status() {
		var startTime: Long = 0L
        var currentFile: Int = 0
        var totalFiles: Int = 0
        var currentSize: Long = 0L
        var totalSize: Long = 0L
		val elapsedTime: Int get() = (System.currentTimeMillis() - startTime).toInt()
		val speed: Double get() {
			return if (elapsedTime == 0) 0.0 else currentSize.toDouble() / (elapsedTime.toDouble() / 1000.0)
		}

		val currentSizeString: String get() = FileSize.toString(currentSize)
		val totalSizeString: String get() = FileSize.toString(totalSize)

		val speedString: String get() = FileSize.toString(speed.toLong()) + "/s"

        val fileRange: String get() = "$currentFile/$totalFiles"
        val sizeRange: String get() = "$currentSizeString/$totalSizeString"
	}

    val createDirectoryCache = hashSetOf<String>()

    fun createDirectories(_path: String, createDirectoryCache: HashSet<String> = PsvitaDevice.createDirectoryCache) {
        val path = _path.replace('\\', '/')
        val parent = File(path).parent
        if (parent != "" && parent != null) {
            createDirectories(parent, createDirectoryCache)
        }
        if (path !in createDirectoryCache) {
            println("Creating directory $path...")
            createDirectoryCache.add(path)
            try {
                connectedFtp().createDirectory(path)
            }
			catch (e: Throwable) {
				try {
					connectedFtp().list(path)
				}
				catch(e: Throwable) {
					println("Could not create remote directory $path")
					throw e
				}
            }
        }
    }

    fun uploadGame(id: String, zip: ZipFile, filter: (path: String) -> Boolean = { true }, updateStatus: (Status) -> Unit = { }) {
        val base = getGameFolder(id)

        val status = Status()

        val unfilteredEntries = zip.entries().toList()

        val filteredEntries = unfilteredEntries.filter { filter(it.name) }

		status.startTime = System.currentTimeMillis()

        status.currentFile = 0
        status.totalFiles = filteredEntries.size

        status.currentSize = 0L
        status.totalSize = filteredEntries.map { it.size }.sum()

        for (entry in filteredEntries) {
            val normalizedName = entry.name.replace('\\', '/')
            val vname = "$base/$normalizedName"
            val directory = File(vname).parent.replace('\\', '/')
            val startSize = status.currentSize
        
            if (!entry.isDirectory) {
                createDirectories(directory)
                print("Writing $vname...")
                try {
                    connectedFtp().upload(vname, zip.getInputStream(entry), 0L, 0L, object : FTPDataTransferListener {
                        override fun started() {
                            print("started...")
                        }

                        override fun completed() {
                             print("completed!")
                             updateStatus(status)
                        }

                        override fun aborted() {
                             print("aborted!")
                             throw it.sauronsoftware.ftp4j.FTPAbortedException();
                        }

                        override fun transferred(size: Int) {
                            status.currentSize += size
                            updateStatus(status)
                        }

                        override fun failed() {
                            print("failed!")
                            throw it.sauronsoftware.ftp4j.FTPDataTransferException();
                        }
                    })
                }
                catch(e: FileNotFoundException) {
                    e.printStackTrace()
                    error("File $vname could not be found")
                    return;
                }
                catch(e: IOException) {
                    e.printStackTrace()
                    throw IOException("An I/O error occured while uploading $vname")
                }
                catch(e: it.sauronsoftware.ftp4j.FTPException) {
                    e.printStackTrace()
                    throw IOException("FTPException: Operation failed: $vname")
                }
                catch(e: it.sauronsoftware.ftp4j.FTPIllegalReplyException) {
                    e.printStackTrace()
                   throw IOException("Illegal reply while uploading $vname")
                }
                catch(e: it.sauronsoftware.ftp4j.FTPDataTransferException) {
                    e.printStackTrace()
                    throw IOException("An I/O error occured while uploading $vname, but connection is still alive")
                }
                catch(e: it.sauronsoftware.ftp4j.FTPAbortedException) {
                    e.printStackTrace()
                    throw IOException("Abort request was sent while uploading $vname")
                }
                println("")
            }
            
            status.currentSize = startSize + entry.size
            status.currentFile++
            updateStatus(status)
        }

        println("DONE. Now package should be promoted!")
    }
/*throws FileNotFoundException
	 *             If the supplied file cannot be found.
	 * @throws IOException
	 *             If an I/O error occurs.
	 * @throws FTPIllegalReplyException
	 *             If the server replies in an illegal way.
	 * @throws FTPException
	 *             If the operation fails.
	 * @throws FTPDataTransferException
	 *             If a I/O occurs in the data transfer connection. If you
	 *             receive this exception the transfer failed, but the main
	 *             connection with the remote FTP server is in theory still
	 *             working.
	 * @throws FTPAbortedException*/
    fun uploadFile(path: String, data: ByteArray, updateStatus: (Status) -> Unit = { }) {
        val status = Status()
        createDirectories(File(path).parent)
		status.startTime = System.currentTimeMillis()
        status.currentFile = 0
        status.totalFiles = 1
        status.totalSize = data.size.toLong()
        updateStatus(status)
        connectedFtp().upload(path, ByteArrayInputStream(data), 0L, 0L, object : FTPDataTransferListener {
            override fun started() {
            }

            override fun completed() {
            }

            override fun aborted() {
            }

            override fun transferred(size: Int) {
                status.currentSize += size
                updateStatus(status)
            }

            override fun failed() {
            }
        })
        status.currentFile++
        updateStatus(status)
    }

    fun promoteVpk(vpkPath: String, displayErrors: Boolean = true): Boolean {
        
        if(vpkPath.isNullOrEmpty()) {
            println("NULL or empty promoting vpk path specified!")
            return false
        }

        println("Promoting: 'PROM $vpkPath'")

        try {
            val reply: FTPReply = connectedFtp().sendCustomCommand("PROM $vpkPath")

            if(reply.getCode() == 502) {
                println("PROM command is not supported by the server")
                if(displayErrors) error("The FTP server does not support promoting/installing VPK files, hence aborting!")
                return false
            }
            else if(reply.getCode() == 500) {
                println("ERROR PROMOTING $vpkPath")
                if(displayErrors) error("The FTP server could not promote/install the VPK file due to an install error, hence aborting!")
                return false
            }
            else if(reply.getCode() != 200) {
                println("Unknown error. Server response: $reply.toString()!")
                if(displayErrors) error("An unknown error occured. Details:\n$reply.toString()")
                return false
            }

             //vitashell replies with code 200 for PROMOTING OK, otherwise 500
            val isOK: Boolean = reply.getCode() == 200
            if(isOK)
                println("FTP server replied: OK PROMOTING")
           
            return isOK
        }
        catch(e: IllegalStateException) {
            println("Promoting, exception: Not connected to the server")
            if(displayErrors) error("It was repliied, that you are not connected to the server, hence aborting!")
        }
        catch(e: IOException) {
            println("Promoting, exception: I/O error")
            if(displayErrors) error("An I/O error occured while promoting/installing the VPK file, hence aborting!")
        }
        catch(e: it.sauronsoftware.ftp4j.FTPIllegalReplyException) {
            println("Promoting, exception: Server responded in a weird way")
            if(displayErrors) error("The server replied something unexpected, hence aborting!")
        }

        return false;
    }

    fun info(text: String) {
		JOptionPane.showMessageDialog(null, text, Texts.format("INFORMATION"), JOptionPane.INFORMATION_MESSAGE)
	}

	fun error(text: String) {
		JOptionPane.showMessageDialog(null, text, "Error", JOptionPane.ERROR_MESSAGE)
	}

	fun warn(title: String, text: String): Boolean {
		val result = JOptionPane.showConfirmDialog(null, text, title, JOptionPane.YES_NO_OPTION)
		return (result == JOptionPane.YES_OPTION)
	}
}
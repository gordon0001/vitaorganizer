package com.soywiz.vitaorganizer

import com.soywiz.util.*
import com.soywiz.vitaorganizer.ext.getBytes
import com.soywiz.vitaorganizer.ext.getInputStream
import com.soywiz.vitaorganizer.ext.getResourceURL
import java.io.File
import java.util.zip.ZipFile

class VpkFile(val vpkFile: File) {
	//val entry: GameEntry by lazy { VpkFile() }

	val paramSfoData: ByteArray by lazy {
		try {
			ZipFile(vpkFile).use { zip ->
				zip.getBytes("sce_sys/param.sfo")
			}
		} catch (e: Throwable) {
			byteArrayOf()
		}
	}

	val psf by lazy {
		try {
			PSF.read(paramSfoData.open2("r"))
		} catch (e: Throwable) {
			hashMapOf<String, Any>()
		}
	}

	val id by lazy { psf["TITLE_ID"].toString() }
	val title by lazy { psf["TITLE"].toString() }
	val hasExtendedPermissions: Boolean by lazy {
		try {
			ZipFile(vpkFile).use { zip ->
				//val ebootBinData = zip.getBytes("eboot.bin")
				val stream = zip.getInputStream("eboot.bin")
				val ret = EbootBin.hasExtendedPermissions(stream)
				stream.close()
				ret
			}
		} catch (e: Throwable) {
			true
		}
	}

	fun cacheAndGetGameId(): String? {
		var retGameId:String? = null
		val retError:String? = null
		try {
			ZipFile(vpkFile).use { zip ->
				val psf = psf
				val zipEntries = zip.entries()
				val gameId = psf["TITLE_ID"].toString()
				retGameId = gameId

				val entry = VitaOrganizerCache.entry(gameId)

				//try to find compressionlevel and vitaminversion or maiversion
				val paramsfo = zip.getEntry("sce_sys/param.sfo")
				val compressionLevel = if (paramsfo != null) paramsfo.method.toString() else ""

				var dumper = DumperNamesHelper().findDumperByShortName(if(psf["ATTRIBUTE"].toString() == "32768") "HB" else "UNKNOWN")
				if(dumper == DumperNames.UNKNOWN) {
					for (file in DumperModules.values()) {
						val suprx = zip.getEntry(file.file)
						if (suprx != null) {
							dumper = DumperNamesHelper().findDumperBySize(suprx.size)
						}
					}
				}

				println("File [$vpkFile] (Dumpver: $dumper)")
				if (!IOMgr.pathExists(entry.compressionFile)) {
					if(IOMgr.createAndCheckFile(entry.compressionFile)) {
						entry.compressionFile.writeText(compressionLevel.toString())
                    }
					else {
						println("Error processing ${vpkFile.name}: could not write compressionFile!")
						return retError;
					}
				}


				if (!IOMgr.pathExists(entry.dumperVersionFile)) {
					if(IOMgr.createAndCheckFile(entry.dumperVersionFile)) {
						entry.dumperVersionFile.writeText(dumper.shortName)
                    }
					else {
						println("Error processing ${vpkFile.name}: could not write dumperVersionFile!")
						return retError;
					}
				}

				if (!IOMgr.pathExists(entry.icon0File)) {
					if(IOMgr.createAndCheckFile(entry.icon0File)) {
						val zipentry = zip.getEntry("sce_sys/icon0.png")
						if(zipentry != null) {
							val stream = zip.getInputStream(zipentry)
							entry.icon0File.writeBytes(stream.readBytes())
							stream.close()
						}
						else {
							println("No icon for ${vpkFile.name}. Writing default one")
							entry.icon0File.writeBytes(getResourceURL("com/soywiz/vitaorganizer/icon.png").readBytes())
						}
					}
					else {
						println("Error processing ${vpkFile.name}: could not write icon0File, but no drama!")
						//return retError;
					}
				}

				if (!IOMgr.pathExists(entry.paramSfoFile)) {
					if(IOMgr.createAndCheckFile(entry.paramSfoFile)) {
						entry.paramSfoFile.writeBytes(paramSfoData)
                    }
					else {
						println("Error processing ${vpkFile.name}: could not write paramSfoFile!")
						return retError;
					}
				}

				if (!IOMgr.pathExists(entry.sizeFile)) {
					if(IOMgr.createAndCheckFile(entry.sizeFile)) {
						val uncompressedSize = zipEntries.toList().map { it.size }.sum()
						entry.sizeFile.writeText("" + uncompressedSize)
					}
					else {
						println("Error processing ${vpkFile.name}: could not write sizeFile!")
						return retError;
					}
				}

				if (!IOMgr.pathExists(entry.permissionsFile)) {
					if(IOMgr.createAndCheckFile(entry.permissionsFile)) {
						val ebootBinStream = zip.getInputStream("eboot.bin")
						entry.permissionsFile.writeText("" + EbootBin.hasExtendedPermissions(ebootBinStream))
						ebootBinStream.close()
					}
					else {
						println("Error processing ${vpkFile.name}: could not write permissionsFile!")
						return retError;
					}
				}


				if(!IOMgr.pathExists(entry.pathFile) && !IOMgr.createAndCheckFile(entry.pathFile)) {
					println("Error processing ${vpkFile.name}: could not create pathFile!")
					return retError;
				}
				if(!IOMgr.canRead(entry.pathFile)) {
					println("Error processing ${vpkFile.name}: could not read existing pathFile!")
					return retError;
				}

				entry.pathFile.writeBytes(vpkFile.absolutePath.toByteArray(Charsets.UTF_8))
				//getGameEntryById(gameId).inPC = true
			}
		} catch (e: Throwable) {
			println("Error processing ${vpkFile.name}")
			e.printStackTrace()
			return retError;
		}
		return retGameId
	}


}
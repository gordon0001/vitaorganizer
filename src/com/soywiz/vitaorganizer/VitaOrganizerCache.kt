package com.soywiz.vitaorganizer

import com.soywiz.util.get
import java.io.File
import java.io.IOException

object VitaOrganizerCache {
    val cacheFolder = File("vitaorganizer/cache")

    init {
        cacheFolder.mkdirs()
    }

	//for gameId there can exist more than 1 entry, for example updates, or dufferent versions of the game
    class Entry(val gameId: String) {
        var icon0File = cacheFolder["$gameId.icon0.png"]
        var paramSfoFile = cacheFolder["$gameId.param.sfo"]
        var pathFile = cacheFolder["$gameId.path"]
        var sizeFile = cacheFolder["$gameId.size"]
        var permissionsFile = cacheFolder["$gameId.extperm"]
        var dumperVersionFile = cacheFolder["$gameId.dumperversion"]
        var compressionFile = cacheFolder["$gameId.compression"]

        init {
            if(!IOMgr.canRead(cacheFolder) && !IOMgr.isDirectory(cacheFolder)) {
				println("Exception: INVALID cache folder. $gameId failed in class Entry::init")
				cacheFolder.mkdirs()
			}
        }

        fun required_validated() : Boolean {
            return IOMgr.canReadWrite(icon0File) 
                && IOMgr.canReadWrite(paramSfoFile)
                && IOMgr.canReadWrite(sizeFile)
				&& IOMgr.canReadWrite(permissionsFile);
				//&& IOMgr.canReadWrite(pathFile)
                //&& IOMgr.canReadWrite(dumperVersionFile)
               // && IOMgr.canReadWrite(compressionFile);
        }

        fun delete() {
            IOMgr.delete(icon0File)
            IOMgr.delete(paramSfoFile)
            IOMgr.delete(pathFile)
            IOMgr.delete(sizeFile)
            IOMgr.delete(permissionsFile)
            IOMgr.delete(dumperVersionFile)
            IOMgr.delete(compressionFile);
        }
    }

    fun entry(gameId: String) = Entry(gameId)

    fun clean(gameId: String) = Entry(gameId).delete();

    fun cleanAll(): Boolean {
		val ret =  IOMgr.delete(cacheFolder)
		cacheFolder.mkdirs();
		return ret
    };

    /*
    fun setIcon0File(titleId: String, data: ByteArray) {
        getIcon0File(titleId).writeBytes(data)
    }

    fun setParamSfoFile(titleId: String, data: ByteArray) {
        getParamSfoFile(titleId).writeBytes(data)
    }

    fun setVpkPath(titleId: String, path: String) {
        getVpkPathFile(titleId).writeBytes(path.toByteArray(Charsets.UTF_8))
    }

    fun getIcon0File(titleId: String): File {
        cacheFolder.mkdirs()
        return cacheFolder["$titleId.icon0.png"]
    }

    fun getParamSfoFile(titleId: String): File {
        cacheFolder.mkdirs()
        return cacheFolder["$titleId.param.sfo"]
    }

    fun getVpkPathFile(titleId: String): File {
        cacheFolder.mkdirs()
        return cacheFolder["$titleId.path"]
    }

    fun getVpkPath(titleId: String): String? {
        return try {
            getVpkPathFile(titleId).readText(Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }
    */
}
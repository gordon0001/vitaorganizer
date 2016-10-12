package com.soywiz.vitaorganizer

import com.soywiz.util.stream
import com.soywiz.util.DumperNamesHelper
import java.io.File

class CachedGameEntry(val gameId: String) {
	val entry = VitaOrganizerCache.entry(gameId)
	val psf by lazy {
		try {
			PSF.read(entry.paramSfoFile.readBytes().stream)
		} catch (e: Throwable) {
			mapOf<String, Any>()
		}
	}
	val hasExtendedPermissions by lazy {
		if(IOMgr.canRead(entry.permissionsFile))
			entry.permissionsFile.readText().toBoolean()
		else
			true
	}
	val attribute by lazy { psf["ATTRIBUTE"].toString() }
	val id by lazy { psf["TITLE_ID"].toString() }
	val title by lazy { psf["TITLE"].toString() }
	val dumperVersion by lazy { 
        var text = "UNKNOWN";
		if(attribute == "32768")
			text = "HB"
    	else if(IOMgr.canRead(entry.dumperVersionFile))
			text = entry.dumperVersionFile.readText(); 

        DumperNamesHelper().findDumperByShortName(text).longName 
    }
	val compressionLevel by lazy { 
        if(IOMgr.canRead(entry.compressionFile))  {
			//see https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
			//4.4.5
			val method = entry.compressionFile.readText() 
			if(method == "0")
				"not compressed"
			else if(method == "1")
				"shrunk"
			else if(method == "2")
				"compression factor 1"
			else if(method == "3")
				"compression factor 2"
			else if(method == "4")
				"compression factor 3"
			else if(method == "5")
				"compression factor 4"
			else if(method == "6")
				"imploded"
			else if(method == "7")
				"reversed"
			else if(method == "8")
				"deflate"
			else if(method == "9")
				"deflate64"
			else
				method
		}
        else {
			//should try to read from zip header too
			"could not read from param.sfo"
        }
    }
	var inVita = false
	var inPC = false
	fun getVpkLocalPath(): String?  {
        if (IOMgr.canRead(entry.pathFile)) {
            val ret: String = entry.pathFile.readText(Charsets.UTF_8)
            if (ret.isEmpty())
                return null
            else
                return ret
        } else {
            return null
        }
    }
	val vpkLocalFile: File? get() = if (getVpkLocalPath() != null) File(getVpkLocalPath()!!) else null
	val vpkLocalVpkFile: VpkFile? get() = if (getVpkLocalPath() != null) VpkFile(File(getVpkLocalPath()!!)) else null
	val size: Long by lazy {
		if(IOMgr.canRead(entry.sizeFile))
			entry.sizeFile.readText().toLong()
		else
			0L
	}

	override fun toString(): String = id
}


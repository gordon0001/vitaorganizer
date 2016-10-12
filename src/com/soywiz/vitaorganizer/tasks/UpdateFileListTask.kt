package com.soywiz.vitaorganizer.tasks

import com.soywiz.util.DumperModules
import com.soywiz.util.DumperNames
import com.soywiz.util.DumperNamesHelper
import com.soywiz.util.open2
import com.soywiz.vitaorganizer.*
import com.soywiz.vitaorganizer.ext.getBytes
import java.io.File
import java.util.zip.ZipFile

class UpdateFileListTask(vitaOrganizer: VitaOrganizer) : VitaTask(vitaOrganizer) {
	override fun perform() {
		synchronized(vitaOrganizer.VPK_GAME_IDS) {
			vitaOrganizer.VPK_GAME_IDS.clear()
		}
		status(Texts.format("STEP_ANALYZING_FILES", "folder" to VitaOrganizerSettings.vpkFolder))

		val MAX_SUBDIRECTORY_LEVELS = 5

		fun listVpkFiles(folder: File, level: Int = 0): List<File> {
			val out = arrayListOf<File>()
			if (level > MAX_SUBDIRECTORY_LEVELS) return out
			for (child in folder.listFiles()) {
				if (child.isDirectory) {
					out += listVpkFiles(child, level = level + 1)
				} else {
					if (child.extension.toLowerCase() == "vpk") out += child
				}
			}
			return out
		}

		val vpkDir = File(VitaOrganizerSettings.vpkFolder);
		if(!IOMgr.pathExists(vpkDir)) {
			error("Invalid VPK directory. Please select a valid one")
			status("Invalid VPK directory. Please select a valid one")
			return
		}
		val vpkFiles = listVpkFiles(vpkDir)
		val maxCount = vpkFiles.count()
		var counter: Int = 0

		for (vpkFile in vpkFiles) {
			++counter;
			status("[$counter/$maxCount] Analyzing ${vpkFile.name}...")
			if(!IOMgr.canRead(vpkFile)) {
				println("Could not open $vpkFile for analyzing...")
			}
			val ff = VpkFile(vpkFile)
			val gameId = ff.cacheAndGetGameId()
			if (gameId != null) {
				if(gameId.length != 9) {
					println("Invalid gameId $gameId")
					//TODO: aztocorrect gameId in param.sfo@vpk
				}
				synchronized(vitaOrganizer.VPK_GAME_IDS) {
					vitaOrganizer.VPK_GAME_IDS += gameId
				}
			}
			else {
				println("Failed to analyze $vpkFile")
			}

			//Thread.sleep(200L)
		}
		vitaOrganizer.updateEntries()
		status(Texts.format("STEP_DONE"))
	}
}
package com.soywiz.vitaorganizer.tasks

import com.soywiz.vitaorganizer.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.*

class DumpEntryTask(vitaOrganizer: VitaOrganizer, val gameId: String) : VitaTask(vitaOrganizer) {
	override fun perform() {

		dumpVpk(gameId)
	}

	fun dumpVpk(gameId: String): Boolean {
		println("starting to dump $gameId")
		val f = File("vitaorganizer/dumps/$gameId")
		if(IOMgr.pathExists(f)) {
			if(IOMgr.delete(f)) {
				println("previous dump folder deleted!")
			}
			else {
				println("failed to remove previous dump, aborting")
				return false
			}
		}
		if(IOMgr.delete("vitaorganizer/dumps/$gameId.vpk"))
			println("deleted previous dumped vpk!");

		status("Starting to dump $gameId folder. This could take a while")
		var ret = ConnectionMgr.downloadDirectory("/ux0:/app/$gameId/", "vitaorganizer/dumps/$gameId/")
		if(!ret) {
			println("failed downloading $gameId")
			status("There was an error while dumping $gameId folder! Task aborted.")
			return false
		}
		status("Finished dumping $gameId folder! Preparing to repack as VPK.")
		println("finished downloading, preparing to repack as vpk!")
		if(IOMgr.delete("vitaorganizer/dumps/$gameId/sce_sys/package")) {
			println("removed package dir")
		}
		else {
			println("could not remove package dir")
			status("There was an error while preparing $gameId folder! Task aborted.")
			return false;
		}
		println("Now packing..");
		status("Now packing to $gameId.vpk");

		ret = ZipMgr.writeZipFile(f, ".VPK")

		if(IOMgr.delete(f)) {
			println("$gameId folder deleted!");
		}
		else {
			println("$gameId folder could not be deleted!")
		}

		if(ret) {
			println("Successfully dumped to $gameId.vpk")
			status("Successfully dumped to $gameId.vpk to ${f.parentFile.canonicalPath}")
			info("Successfully dumped to $gameId.vpk to\n${f.parentFile.canonicalPath}")
		}
		else {
			println("Failed to dump $gameId.vpk")
			status("Failed to dump $gameId.vpk")
			info("Failed to dump $gameId.vpk")
			return false
		}
		return true
	}
}
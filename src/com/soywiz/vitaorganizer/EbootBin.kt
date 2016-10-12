package com.soywiz.vitaorganizer

import java.io.InputStream

object EbootBin {
    fun hasExtendedPermissions(s: InputStream): Boolean {
		try {
			val authid = ByteArray(1)

			s.skip(0x80)
			val ret = s.read(authid)

			if (ret != 1) {
				println("hasExtendedPermissions::read failed")
				return true
			}
			if (authid[0].toInt() == 2) {
				return false
			}
			else
				return true
		}
		catch (e: Throwable) {
			println("hasExtendedPermissions, exception arised")
			e.printStackTrace()
			return true
		}
	}
}
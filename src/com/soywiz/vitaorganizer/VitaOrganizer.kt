/*
	TODO: 
		WORKING ON IT Improve: handling of the ftp related stuff (connecting, authenticating, disconnecting, error checking and handling, error messages, ...)
		WORKING ON IT Add/Improve/Fix: generally strict error handling, display critical errors to the user
		DONE Fix: Deleting files on the remote host
		DONE Fix: weird way of trying to create dictectories on the remote host
			 check for exisiting directories. Example of the weirdness (happen all in the same promoting task):
			 	Creating directory /...                                                                                                 
				it.sauronsoftware.ftp4j.FTPException [code=550, message= Could not create the directory.]
			 followed by
				Creating directory /ux0:...                                                                                             
				it.sauronsoftware.ftp4j.FTPException [code=550, message= Could not create the directory.]
			 followed by
			 	Creating directory /ux0:/organizer...                                                                                   
				it.sauronsoftware.ftp4j.FTPException [code=550, message= Could not create the directory.]
			 finally
			 	Promoting: 'PROM ux0:/organizer/ROWS00031.VPK'                                                                          
				(that check was added by me before:) FTP server replied: OK PROMOTING
				Can't delete ux0:/organizer/ROWS00031.VPK                                                                               
				it.sauronsoftware.ftp4j.FTPException [code=550, message= Could not delete the file.] 
		Add/Fix: Abort files transfers on request and when pressing "Disconnect from"
			 The current status of this is when you press the disconnect button it
			 terminates the connection to port 1337, but not the file transfer connection
			 (for file transferes there will be automatically a new connnection only for this purpose)
			 I tell it to terminate it too but it wont, so it actually transfers files with success even when smashing the Disconnect button
		Add/Improve: abort curremt and cancel queued tasks, aswell as showing current and queued tasks
		DONE Fix/Improve: automaitcally refreshing of the GameListTable should be reliable, or be implemented like refreshing after specific tasks have finished
		HAPPENS SOON WHEN I REWRITE THE CACHE SYSTEM Fix: differenziere Base und Update VPKS, ein Weg um sie automatisch nacheinander zu installieren (oder in
			 ein neues VPK zu vereinen)
		DONE Add/improve: specifiy ftp server port by adding :<port> to the ip/hostname, like 192.168.2.10:1234 or PSVITA:1234
					 store them aswell in the exisiting variable 'lastDevicePort' in VitaOrganizerSettings for saving/restoring the port
		Add: Settings dialog for various future user setable options
		Add: info/debug/log window with different options what to display/filter
		HALF DONE Add: Delete cache files for specific entries, check their validaty or remove or rebuild all cache files
			 Detect changes in filenames and checksum and update cache properly
		Fix: check existing of the VPK folder and if we have read, write and delete permissions at startup
			 If we have only read permissions let the user choose a directory where to store repacked VPK files
		DONE Fix: disable in game entry contextmenu popup the option "Show on desktop" when there is no or invalid path
		Add: Show warning when ID of a VPK file (present in param.sfo) is not 9 characters long
			 VitaShell will refuse install/promote them. Additionally give the ability to fix those IDs
		Add: Check validaty of a VPK file
		Add: Renaming of VPK files in a specific (user) chosen format like ID_TITLE_VERSION.vpk
		 	 or REGION_ID_TITLE_VERSION or TITLE_ID_VERSION and so on
		Add: PSF editor
		NEARLY DONE Add: dumping of remote homebrew/pirated games for converting back into a vpk file
		Add: recognize maidumptool directory and add the ability to upload them to ux0:/mai
			 Optionally if possible convert mai backups to installable VPK files
		Add: SQLITE3 editor and viewer to display or modify for example app.db
		Add: Show Henkaku version, offer a http server with the possibility to install henkaku v1 to 6 via vitas webbrowser 
			 or install/upload a local version with installing henkaku by opening an email
		Add: File browser with the abilities of a ftp client
		Add: Savegame browser and the ability to backup/restore them
		Add: Deep vpk file scan on harddisk, optionally specify the level
		!(Add: Own customized (ftp) server on the vita that gives us more possibilities to do everything we want)!
		Add: delete homebrew or pirated games

*/

package com.soywiz.vitaorganizer

import com.soywiz.util.OS
import com.soywiz.util.open2
import com.soywiz.vitaorganizer.ext.action
import com.soywiz.vitaorganizer.ext.getResourceURL
import com.soywiz.vitaorganizer.ext.openWebpage
import com.soywiz.vitaorganizer.ext.showDialog
import com.soywiz.vitaorganizer.popups.AboutFrame
import com.soywiz.vitaorganizer.popups.KeyValueViewerFrame
import com.soywiz.vitaorganizer.tasks.*
import java.awt.*
import java.awt.event.*
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.*
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

class VitaOrganizer : JPanel(BorderLayout()), StatusUpdater {
	companion object {
		@JvmStatic fun main(args: Array<String>) {
			VitaOrganizer().start()
		}
	}

	val vitaOrganizer = this@VitaOrganizer
	val localTasks = VitaTaskQueue(this)
	val remoteTasks = VitaTaskQueue(this)

	init {
		Texts.setLanguage(VitaOrganizerSettings.LANGUAGE_LOCALE)
		UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
	}

	val frame = JFrame("VitaOrganizer ${VitaOrganizerVersion.currentVersion}.dev-01").apply {
		defaultCloseOperation = JFrame.EXIT_ON_CLOSE
		iconImage = ImageIO.read(getResourceURL("com/soywiz/vitaorganizer/icon.png"))
	}

	fun start() {
		frame.pack()
		frame.setLocationRelativeTo(null)
		frame.isVisible = true
	}

	init {
		//Create and set up the content pane.
		val newContentPane = this
		newContentPane.isOpaque = true //content panes must be opaque
		frame.contentPane = newContentPane

		//Display the window.

		//}
	}

	val VPK_GAME_IDS = hashSetOf<String>()
	val VITA_GAME_IDS = hashSetOf<String>()

	val statusLabel = JLabel(Texts.format("STEP_STARTED"))

	override fun updateStatus(status: String) {
		//println(status)
		SwingUtilities.invokeLater {
			statusLabel.text = status
		}
	}

	fun updateEntries() {
		val ALL_GAME_IDS = LinkedHashMap<String, CachedGameEntry>()

		fun getGameEntryById(gameId: String) = ALL_GAME_IDS.getOrPut(gameId) { CachedGameEntry(gameId) }

		synchronized(VPK_GAME_IDS) {
			for (gameId in VPK_GAME_IDS) {
				getGameEntryById(gameId).inPC = true
			}
		}
		synchronized(VITA_GAME_IDS) {
			for (gameId in VITA_GAME_IDS) {
				getGameEntryById(gameId).inVita = true
			}
		}

		table.setEntries(ALL_GAME_IDS.values.toList())
	}

	fun syncEntries(block: Boolean = false) : Boolean {
		if(!ConnectionMgr.isConnected() || !ConnectionMgr.isAuthenticated()) {
			println("Vita sync entries: not connected to server")
			return false
        }

		synchronized(VITA_GAME_IDS) {
			VITA_GAME_IDS.clear()
		}

		var done = false
		var updated = false
		var finally_done = false
		var error = false
		Thread {
			try {
				var vitaGameCount = 0
				val vitaGameIds = PsvitaDevice.getGameIds()
				for (gameId in vitaGameIds) {
					updateStatus(Texts.format("PROCESSING_GAME", "current" to (vitaGameCount + 1), "total" to vitaGameIds.size, "gameId" to gameId, "id" to gameId))
					//println(gameId)
					try {
						PsvitaDevice.getParamSfoCached(gameId)
						PsvitaDevice.getGameIconCached(gameId)
						val entry2 = VitaOrganizerCache.entry(gameId)
						val sizeFile = entry2.sizeFile
						if (!sizeFile.exists()) {
							sizeFile.writeText("" + PsvitaDevice.getGameSize(gameId))
						}

						if (!entry2.permissionsFile.exists()) {
							val ebootBin = PsvitaDevice.downloadEbootBin(gameId)
							try {
								val stream = ebootBin.inputStream()
								entry2.permissionsFile.writeText("" + EbootBin.hasExtendedPermissions(stream))
								stream.close()
							} catch (e: Throwable) {
								entry2.permissionsFile.writeText("true")
							}
						}

						synchronized(VITA_GAME_IDS) {
							VITA_GAME_IDS += gameId
						}
						updated = true
						vitaGameCount++

						//val entry = getGameEntryById(gameId)
						//entry.inVita = true
					} catch (e: Throwable) {
						e.printStackTrace()
						continue
					}

				}
				when (vitaGameCount) {
                    0 -> error = true
                }
			} finally {
				done = true
				updated = true
				updateStatus(Texts.format("STEP_DONE"))
			}
		}.start()

		Thread {
			do {
				while (!updated) {
					Thread.sleep(100)
				}
				updated = false
				updateEntries()
			} while (!done)

			updateEntries()
			finally_done = true
		}.start()

		if(block) {
			while(!finally_done)
				Thread.sleep(10)
		}

		return !error
	}

	fun showFileInExplorerOrFinder(file: File) {
		if(!IOMgr.pathExists(file))
			return;

		if (OS.isWindows) {
			ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start().waitFor()
		} else {
			ProcessBuilder("open", "-R", file.absolutePath).start().waitFor()
		}
	}

	val table = object : GameListTable() {
		val dialog = this@VitaOrganizer
		val gameTitlePopup = JMenuItem("").apply {
			this.isEnabled = false
		}
		val gameDumperVersionPopup = JMenuItem("").apply {
			this.isEnabled = false
		}
		val gameCompressionLevelPopup = JMenuItem("").apply {
			this.isEnabled = false
		}

		val popupMenu = object : JPopupMenu() {
			var entry: CachedGameEntry? = null

			val deleteFromVita = JMenuItem(Texts.format("DELETE_FROM_PSVITA_ACTION")).action {
				val entry = entry
				if (entry != null) {
					val info = mapOf("title" to entry.title)
					JOptionPane.showConfirmDialog(
						dialog,
						Texts.formatMap("DELETE_FROM_PSVITA_MESSAGE", info),
						Texts.formatMap("DELETE_FROM_PSVITA_TITLE", info),
						JOptionPane.OK_CANCEL_OPTION, JOptionPane.OK_OPTION
					)
				}
				//this.isEnabled = false
			}

			val sendVpkToVita = JMenuItem(Texts.format("SEND_PROMOTING_VPK_TO_VITA_ACTION")).action {
				if (entry != null && entry!!.vpkLocalVpkFile != null) remoteTasks.queue(SendPromotingVpkToVitaTask(vitaOrganizer, entry!!.vpkLocalVpkFile!!))
				else println("remoteTask SendPromotingVpkToVita failed to queue")
			}

			val sendDataToVita = JMenuItem(Texts.format("SEND_DATA_TO_VITA_ACTION")).action {
				if (entry != null && entry!!.vpkLocalVpkFile != null) remoteTasks.queue(SendDataToVitaTask(vitaOrganizer, entry!!.vpkLocalVpkFile!!))
				else println("remoteTask SendDatatoVita failed to queue")
			}

			val sendToVita1Step = JMenuItem(Texts.format("SEND_FULL_APP_TO_VITA_ACTION")).action {
				if (entry != null && entry!!.vpkLocalVpkFile != null) remoteTasks.queue(OneStepToVitaTask(vitaOrganizer, entry!!.vpkLocalVpkFile!!))
				else println("remoteTask OneStepToVita failed to queue")
			}

			val showInFilebrowser = JMenuItem(if (OS.isWindows) Texts.format("MENU_SHOW_EXPLORER") else Texts.format("MENU_SHOW_FINDER")).action {
				if (entry != null) {
					if(entry!!.inPC && entry!!.vpkLocalVpkFile != null) {
						showFileInExplorerOrFinder(entry!!.vpkLocalFile!!)
					}
				}
			}

			val repackVpk = JMenuItem(Texts.format("MENU_REPACK")).action {
				if (entry != null && entry!!.vpkLocalVpkFile != null) remoteTasks.queue(RepackVpkTask(vitaOrganizer, entry!!, setSecure = true))
			}

			val showPSF = JMenuItem(Texts.format("MENU_SHOW_PSF")).action {
				if (entry != null) {
					frame.showDialog(KeyValueViewerFrame(Texts.format("PSF_VIEWER_TITLE", "id" to entry!!.id, "title" to entry!!.title), entry!!.psf))
				}
			}

			val dumpToVpk =  JMenuItem("Dump to VPK").action {
				if (entry != null) {
					remoteTasks.queue(DumpEntryTask(vitaOrganizer, entry!!.gameId))
				}
			}

			val deleteVpk = JMenuItem("Delete VPK").action {
				if(entry != null) {
					if(entry!!.vpkLocalFile != null && IOMgr.canDelete(entry!!.vpkLocalFile!!)){
						if(PsvitaDevice.warn("Confirm deletion", "Are you sure you want to delete ${entry!!.vpkLocalFile}?\nThis operation cannot be undone!")) {
							if(IOMgr.delete(entry!!.vpkLocalFile!!)) {
								synchronized(VPK_GAME_IDS) {
									VPK_GAME_IDS.remove(entry!!.gameId)
								}
								val filepath = entry!!.vpkLocalFile!!
								entry!!.entry.delete()
								updateEntries()
								updateStatus("$filepath was successfully deleted!")
							}
							else {
								PsvitaDevice.error("Could not delete ${entry!!.vpkLocalFile!!}!")
							}
						}
					}
					else {
						PsvitaDevice.error("Not deletable!")
					}
				}
			}

			init {
				add(gameTitlePopup)
				add(JSeparator())
				add(gameDumperVersionPopup)
				add(gameCompressionLevelPopup)
				add(JSeparator())
				add(showInFilebrowser)
				add(showPSF)
				add(repackVpk)
				add(dumpToVpk)
				add(deleteVpk)

				add(JSeparator())
				add(JMenuItem(Texts.format("METHOD1_INFO")).apply {
					isEnabled = false
				})
				add(sendVpkToVita)
				add(sendDataToVita)
				add(JSeparator())
				add(JMenuItem(Texts.format("METHOD2_INFO")).apply {
					isEnabled = false
				})
				add(sendToVita1Step)
			}

			override fun show(invoker: Component?, x: Int, y: Int) {
				val entry = entry
				gameTitlePopup.text = Texts.format("UNKNOWN_VERSION")
				gameDumperVersionPopup.text = Texts.format("UNKNOWN_VERSION")
				gameCompressionLevelPopup.text = Texts.format("UNKNOWN_VERSION")
				deleteFromVita.isEnabled = false
				sendVpkToVita.isEnabled = false
				sendDataToVita.isEnabled = false
				sendToVita1Step.isEnabled = false
				showInFilebrowser.isEnabled = false
				repackVpk.isEnabled = false
				showPSF.isEnabled = false
				dumpToVpk.isEnabled = false
				deleteVpk.isEnabled = false

				if (entry != null) {
					gameDumperVersionPopup.text = Texts.format("DUMPER_VERSION", "version" to entry.dumperVersion)
					gameCompressionLevelPopup.text = Texts.format("COMPRESSION_LEVEL", "level" to entry.compressionLevel)
					gameTitlePopup.text = "[${entry.id}] ${entry.title} " + (entry.psf["APP_VER"] ?: entry.psf["VERSION"] ?: "")
					gameTitlePopup.setForeground(Color(64, 0,255))
					gameTitlePopup.setFont(gameTitlePopup.getFont().deriveFont(Font.BOLD))
					deleteFromVita.isEnabled = entry.inVita
					sendVpkToVita.isEnabled = entry.inPC
					sendDataToVita.isEnabled = entry.inPC
					sendToVita1Step.isEnabled = entry.inPC
					showInFilebrowser.isEnabled = entry.inPC
					repackVpk.isEnabled = entry.inPC
					showPSF.isEnabled = true
					dumpToVpk.isEnabled = entry.inVita
					deleteVpk.isEnabled = entry.inPC
				}

				super.show(invoker, x, y)
			}
		}

		override fun showMenuAtFor(x: Int, y: Int, entry: CachedGameEntry) {
			popupMenu.entry = entry
			popupMenu.show(this.table, x, y)
		}

		init {
			//this.componentPopupMenu = popupMenu
		}
	}

	fun selectFolder() {
		val chooser = JFileChooser()
		chooser.currentDirectory = File(if(IOMgr.canRead(VitaOrganizerSettings.vpkFolder)) VitaOrganizerSettings.vpkFolder else ".")
		chooser.dialogTitle = Texts.format("SELECT_PSVITA_VPK_FOLDER")
		chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
		chooser.isAcceptAllFileFilterUsed = false
		//chooser.selectedFile = File(VitaOrganizerSettings.vpkFolder)
		val result = chooser.showOpenDialog(this@VitaOrganizer)
		if (result == JFileChooser.APPROVE_OPTION) {
			if(IOMgr.canRead(chooser.selectedFile.absolutePath)) {
				VitaOrganizerSettings.vpkFolder = chooser.selectedFile.absolutePath
				updateFileList()
				syncEntries()
			}
		}
	}

	val filterTextField = object : JTextField("") {
		init {
			font = Font(Font.MONOSPACED, Font.PLAIN, 13)
			columns = 12
		}

		override fun processKeyEvent(e: KeyEvent?) {
			super.processKeyEvent(e)
			table.filter = this.text
		}
	}.apply {
		//addActionListener {
		//	println("aaa")
		//}
	}

	fun setLanguageText(name: String) {
		VitaOrganizerSettings.LANGUAGE = name
		restart()
	}

	fun restart() {
		this.frame.isVisible = false
		this.frame.dispose()
		VitaOrganizer().start()
	}

	fun cleanFileCache() : Boolean {
		return VitaOrganizerCache.cleanAll();
	}

	fun cleanVPKCache() {
		synchronized(VPK_GAME_IDS) {
			for(id in VPK_GAME_IDS) {
				VitaOrganizerCache.entry(id).delete()
			}
			VPK_GAME_IDS.clear()
		}
		updateEntries()
	}

	fun cleanVitaCache() {
		synchronized(VITA_GAME_IDS) {
			for(id in VITA_GAME_IDS) {
				VitaOrganizerCache.entry(id).delete()
			}
			VITA_GAME_IDS.clear()
		}
		updateEntries()
	}

	fun cleanMemoryCache() {
		synchronized(VPK_GAME_IDS) {
			VPK_GAME_IDS.clear()
		}
		synchronized(VITA_GAME_IDS) {
			VITA_GAME_IDS.clear()
		}
		updateEntries()
	}

	fun cleanGameListTable() {
		table.model2.setRowCount(0)
		table.model2.fireTableDataChanged()
	}

	fun cleanAllCaches() {
		cleanFileCache()
		cleanMemoryCache()
		cleanGameListTable()
	}

	fun rebuildCache() {
		cleanAllCaches();
		updateFileList()
		syncEntries()
	}

	init {
		frame.jMenuBar = JMenuBar().apply {
			add(JMenu(Texts.format("MENU_FILE")).apply {
				add(JMenuItem(Texts.format("MENU_INSTALL_VPK")).action {
					val chooser = JFileChooser()
					chooser.currentDirectory = File(VitaOrganizerSettings.vpkFolder)
					chooser.dialogTitle = Texts.format("SELECT_PSVITA_VPK_FOLDER")
					chooser.fileFilter = FileNameExtensionFilter(Texts.format("FILEFILTER_DESC_VPK_FILES"), "vpk")
					chooser.fileSelectionMode = JFileChooser.FILES_ONLY
					//chooser.isAcceptAllFileFilterUsed = false
					//chooser.selectedFile = File(VitaOrganizerSettings.vpkFolder)
					val result = chooser.showOpenDialog(this@VitaOrganizer)
					if (result == JFileChooser.APPROVE_OPTION) {
						remoteTasks.queue(OneStepToVitaTask(this@VitaOrganizer, VpkFile(chooser.selectedFile)))
					}
				})
				add(JMenuItem(Texts.format("MENU_SELECT_FOLDER")).action {
					selectFolder()
				})
				add(JMenuItem(Texts.format("MENU_REFRESH")).action {
					updateFileList()
				})
				add(JSeparator())
				add(JMenuItem(Texts.format("MENU_EXIT")).action {
					System.exit(0)
				})
			})
			add(JMenu(Texts.format("MENU_SETTINGS")).apply {
				add(JMenu(Texts.format("MENU_LANGUAGES")).apply {
					add(JRadioButtonMenuItem(Texts.format("MENU_LANGUAGE_AUTODETECT")).apply {
						this.isSelected = VitaOrganizerSettings.isLanguageAutodetect
					}.action {
						setLanguageText("auto")
					})
					add(JSeparator())
					for (l in Texts.SUPPORTED_LOCALES) {
						val lrb = JRadioButtonMenuItem(l.getDisplayLanguage(l).capitalize()).apply {
							this.isSelected = VitaOrganizerSettings.LANGUAGE_LOCALE == l
						}
						//languageList[l.language] = lrb
						add(lrb).action {
							setLanguageText(l.language)
						}
					}
					Unit
				})
				add(JMenu("Cache").apply {
					add(JMenuItem("Clean all cached data")).action {
						cleanAllCaches()
					}
					add(JSeparator())
					add(JMenuItem("Clean cache on disk")).action {
						cleanFileCache()
					}
					add(JMenuItem("Clean cache in memory")).action {
						cleanMemoryCache()
					}
					add(JSeparator())
					add(JMenuItem("Clean VPK cache")).action {
						cleanVPKCache()
					}
					add(JMenuItem("Clean Vita cache")).action {
						cleanVitaCache()
					}
					add(JSeparator())
					add(JMenuItem("Rebuild cache")).action {
						rebuildCache()
					}
				})
				add(JMenu("Server").apply {
					add(JCheckBoxMenuItem("Auto-connect on start", false)).action {
						VitaOrganizerSettings.autoConnect = if(this.isSelected) "true" else "false"
					}.apply {
						val autoconnect = try { VitaOrganizerSettings.autoConnect.toBoolean() } catch (t: Throwable) { VitaOrganizerSettings.autoConnect = "false"; false }
						this.isSelected = autoconnect
					}
					add(JCheckBoxMenuItem("Keep connection alive", false)).action {
						VitaOrganizerSettings.antiIdle = if(this.isSelected) "true" else "false"
					}.apply {
						val antiidle = try { VitaOrganizerSettings.antiIdle.toBoolean() } catch (t: Throwable) { VitaOrganizerSettings.antiIdle = "false"; false }
						this.isSelected = antiidle
					}
				})
			})
			add(JMenu(Texts.format("MENU_HELP")).apply {
				add(JMenuItem(Texts.format("MENU_WEBSITE")).action {
					openWebpage(URL("http://github.com/soywiz/vitaorganizer"))
				})
				add(JSeparator())
				add(JMenuItem(Texts.format("MENU_CHECK_FOR_UPDATES")).action {
					checkForUpdates()
				})
				add(JMenuItem(Texts.format("MENU_ABOUT")).action {
					openAbout()
				})
			})
		}

		//val columnNames = arrayOf("Icon", "ID", "Title")

		//val data = arrayOf(arrayOf(JLabel("Kathy"), "Smith", "Snowboarding", 5, false), arrayOf("John", "Doe", "Rowing", 3, true), arrayOf("Sue", "Black", "Knitting", 2, false), arrayOf("Jane", "White", "Speed reading", 20, true), arrayOf("Joe", "Brown", "Pool", 10, false))


		table.table.preferredScrollableViewportSize = Dimension(800, 620)
		//table.rowSelectionAllowed = false
		//table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

		//Create the scroll pane and add the table to it.
		val scrollPane = table

		//Add the scroll pane to this panel.
		//val const = SpringLayout.Constraints()
		//const.setConstraint(SpringLayout.NORTH, hSpring.constant(32, 32, 32))
		//const.height = Spring.constant(32, 32, 32)

		val footer = JPanel().apply {
			add(statusLabel)
		}

		val header = JPanel(FlowLayout(FlowLayout.LEFT)).apply {

			add(JButton(Texts.format("MENU_SELECT_FOLDER")).action {
				selectFolder()
			})

			add(JButton(Texts.format("MENU_REFRESH")).action {
				updateFileList()
				syncEntries()
			})

			fun getFormattedHostname(): String {
				try {
					val port = VitaOrganizerSettings.lastDevicePort
					if(port.toInt() == 1337)
						return VitaOrganizerSettings.lastDeviceIp
					else {
						return VitaOrganizerSettings.lastDeviceIp + ":" + port
					}
				}
				catch(e: Throwable) {
					
				}
				return VitaOrganizerSettings.lastDeviceIp
			}

			val connectText = Texts.format("CONNECT_TO_PSVITA")

			val connectAddress = object : JTextField(getFormattedHostname()) {
				init {
					font = Font(Font.MONOSPACED, Font.PLAIN, 14)
					columns = 17;
				}

				override fun processKeyEvent(e: KeyEvent?) {
					super.processKeyEvent(e)
					var ip = this.getText()

					if(ip.contains(':')) {
						val s = ip.substringAfterLast(':')
						ip = ip.substringBefore(':')
						if(ip.length > 0)
							VitaOrganizerSettings.lastDevicePort = s;
						else
							VitaOrganizerSettings.lastDevicePort = "1337"
					}

					VitaOrganizerSettings.lastDeviceIp = ip
				}
			}.apply {
				addActionListener {
					println("aaa")
				}
			}

			val connectButton = object : JButton(connectText) {
				val button = this

				fun disconnect() {
					updateStatus(Texts.format("DISCONNECTING"))

                    if(ConnectionMgr.disconnectFromFtp()) {
					    button.text = connectText
					    synchronized(VITA_GAME_IDS) {
						    VITA_GAME_IDS.clear()
					    }
					    updateEntries()
					    updateStatus(Texts.format("DISCONNECTED"))
                    }
                    else
                        println("Failed to disconnect!")
				}

				fun connect(ip: String, port: Int) {
					updateStatus(Texts.format("CONNECTING"))

					if(!ConnectionMgr.connectToFtp(ip, port)) {
						PsvitaDevice.error("Could not connect to $ip:$port")
						return;
					}

					updateStatus(Texts.format("CONNECTED"))
					button.text = Texts.format("DISCONNECT_FROM_IP", "ip" to ip)

					VitaOrganizerSettings.lastDeviceIp = ip
					VitaOrganizerSettings.lastDevicePort = java.lang.String.valueOf(port);

					syncEntries()
				}

				init {
					val button = this
                    
                    addActionListener al@ {e:ActionEvent ->
					    if (ConnectionMgr.isConnected() && ConnectionMgr.isAuthenticated()) {
						    disconnect()
					    } else {
							if(ConnectionMgr.isConnected() && ConnectionMgr.isAuthenticated())
								return@al

                            var ip = connectAddress.getText()
							var port: Int = 1337
                                
                            if(ip == "") {
                                println("No ip given")
                                JOptionPane.showMessageDialog(frame, "Please type in an ip address!")
                                return@al
                            }

							if(ip.contains(':')) {
								val s = ip.substringAfterLast(':')
								ip = ip.substringBefore(':')
								println("ip: $ip port $s")
								port = s.toInt()
							}

                            
                            if(!ConnectionMgr.checkAddress(ip, port, 3000)) {
                                println("No connection could be etablished!");
                                JOptionPane.showMessageDialog(frame, "No connection could be etablished!\nCheck your ip or start the FTP server!")
                                return@al
                            }
                                
						    button.button.isEnabled = false
						    connect(ip, port)
						    button.button.isEnabled = true
                       
								/*
								if (PsvitaDevice.checkAddress(VitaOrganizerSettings.lastDeviceIp)) {
								} else {
									Thread {
										val ips = PsvitaDevice.discoverIp()
										println("Discovered ips: $ips")
										if (ips.size >= 1) {
											connect(ips.first())
										}
										button.button.isEnabled = true
									}.start()
								}
								*/
					    }
					}
				}
			}

			add(connectButton)
			add(connectAddress)
			add(JLabel(Texts.format("LABEL_FILTER")))
			add(filterTextField)

			//filterTextField.requestFocus()
		}

		add(header, SpringLayout.NORTH)
		add(scrollPane)
		add(footer, SpringLayout.SOUTH)

		updateFileList()

		val ip = VitaOrganizerSettings.lastDeviceIp
		val port = try { VitaOrganizerSettings.lastDevicePort.toInt() } catch (t: Throwable) { VitaOrganizerSettings.lastDevicePort = "1337"; 1337 }
		val autoconnect = VitaOrganizerSettings.autoConnect.toBoolean()

		if(autoconnect) {
			if(!ConnectionMgr.checkAddress(ip, port, 3000)) {
				println("No connection could be etablished!");
			}
			else {
				updateStatus("Connecting to $ip:$port")
				if(ConnectionMgr.connectToFtp(ip, port)) {
					updateStatus("Connected!")
					syncEntries()
				}
			}
		}

		frame.addWindowListener(object : WindowAdapter() {
			override fun windowOpened(e: WindowEvent) {
				filterTextField.requestFocus()
			}
		})

		//frame.focusOwner = filterTextField
	}

	fun openAbout() {
		frame.showDialog(AboutFrame())
	}

	fun checkForUpdates() {
		localTasks.queue(CheckForUpdatesTask(vitaOrganizer))
	}

	fun updateFileList() {
		localTasks.queue(UpdateFileListTask(vitaOrganizer))
	}


	//fun fileWatchFolder(path: String) {
	//	val watcher = FileSystems.getDefault().newWatchService()
	//	val dir = FileSystems.getDefault().getPath(path)
	//	try {
	//
	//		val key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
	//
	//	} catch (x: IOException) {
	//		System.err.println(x);
	//	}
	//}
}

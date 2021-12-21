package com.naman14.timberx.sdl

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.net.toUri
import com.naman14.timberx.R
import com.naman14.timberx.constants.Constants
import com.naman14.timberx.constants.Constants.ALBUM_ID
import com.naman14.timberx.constants.Constants.ARTIST_ID
import com.naman14.timberx.constants.Constants.PLAYLIST_ID
import com.naman14.timberx.playback.TimberMusicService
import com.naman14.timberx.repository.*
import com.smartdevicelink.managers.SdlManager
import com.smartdevicelink.managers.SdlManagerListener
import com.smartdevicelink.managers.file.filetypes.SdlArtwork
import com.smartdevicelink.managers.lifecycle.LifecycleConfigurationUpdate
import com.smartdevicelink.managers.screen.AlertView
import com.smartdevicelink.managers.screen.OnButtonListener
import com.smartdevicelink.managers.screen.SoftButtonObject
import com.smartdevicelink.managers.screen.SoftButtonObject.OnEventListener
import com.smartdevicelink.managers.screen.choiceset.ChoiceCell
import com.smartdevicelink.managers.screen.choiceset.ChoiceSet
import com.smartdevicelink.managers.screen.choiceset.ChoiceSetSelectionListener
import com.smartdevicelink.managers.screen.menu.MenuCell
import com.smartdevicelink.protocol.enums.FunctionID
import com.smartdevicelink.proxy.RPCNotification
import com.smartdevicelink.proxy.rpc.*
import com.smartdevicelink.proxy.rpc.enums.*
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener
import com.smartdevicelink.transport.BaseTransportConfig
import com.smartdevicelink.transport.MultiplexTransportConfig
import com.smartdevicelink.transport.TCPTransportConfig
import com.smartdevicelink.util.DebugTool
import com.smartdevicelink.util.SystemInfo
import org.koin.android.ext.android.inject
import org.koin.standalone.KoinComponent
import timber.log.Timber
import java.io.File
import java.util.*

class SdlService : Service(), KoinComponent {
    // variable to create and call functions of the SyncProxy
    private var sdlManager: SdlManager? = null
    private var choiceCellList: List<ChoiceCell>? = null
    private var ready = false
    private var metadataInitialized = false

    private val missingArt = SdlArtwork("missing_art.png", FileType.GRAPHIC_PNG, R.drawable.icon, false)
    private val shuffleOffArt = SdlArtwork("shuffle_off.png", FileType.GRAPHIC_PNG, R.drawable.shuffle_none, true)
    private val shuffleOnArt = SdlArtwork("shuffle_on.png", FileType.GRAPHIC_PNG, R.drawable.shuffle_all, true)
    private val repeatOffArt = SdlArtwork("repeat_off.png", FileType.GRAPHIC_PNG, R.drawable.repeat_none, true)
    private val repeatOneArt = SdlArtwork("repeat_one.png", FileType.GRAPHIC_PNG, R.drawable.repeat_one, true)
    private val repeatOnArt = SdlArtwork("repeat_on.png", FileType.GRAPHIC_PNG, R.drawable.repeat_all, true)

    private val albumRepository by inject<AlbumRepository>()
    private val artistRepository by inject<ArtistRepository>()
    private val songsRepository by inject<SongsRepository>()
    private val genreRepository by inject<GenreRepository>()
    private val playlistRepository by inject<PlaylistRepository>()

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterForeground()
        }
    }

    // Helper method to let the service enter foreground mode
    @SuppressLint("NewApi")
    fun enterForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(APP_ID, "SdlService", NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel)
                val serviceNotification: Notification = Notification.Builder(this, channel.id)
                        .setContentTitle("Connected through SDL")
                        .setSmallIcon(R.drawable.ic_sdl)
                        .build()
                startForeground(FOREGROUND_SERVICE_ID, serviceNotification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand(): ${intent?.action}")
        if (sdlManager == null || !ready) {
            startProxy()
            return START_STICKY
        }

        when (intent?.action) {
            Constants.ACTION_NOW_PLAYING -> {
                onNowPlaying(intent?.extras)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true)
        }
        if (sdlManager != null) {
            sdlManager!!.dispose()
        }
        super.onDestroy()
    }

    private fun startProxy() {
        // This logic is to select the correct transport and security levels defined in the selected build flavor
        // Build flavors are selected by the "build variants" tab typically located in the bottom left of Android Studio
        // Typically in your app, you will only set one of these.
        if (sdlManager == null) {
            Log.i(TAG, "Starting SDL Proxy")
            // Enable DebugTool for debug build type
            DebugTool.enableDebugTool()
            var transport = //TCPTransportConfig(12345, "127.0.0.1", true)
            MultiplexTransportConfig(this, APP_ID, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF)
            transport.setRequiresAudioSupport(false)

            // The app type to be used
            val appType = Vector<AppHMIType>()
            appType.add(AppHMIType.MEDIA)
            val context = this

            // The manager listener helps you know when certain events that pertain to the SDL Manager happen
            // Here we will listen for ON_HMI_STATUS and ON_COMMAND notifications
            val listener: SdlManagerListener = object : SdlManagerListener {
                override fun onStart() {
                    // HMI Status Listener
                    ready = true
                    sdlManager!!.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, object : OnRPCNotificationListener() {
                        override fun onNotified(notification: RPCNotification) {
                            val onHMIStatus = notification as OnHMIStatus
                            if (onHMIStatus.windowID != null && onHMIStatus.windowID != PredefinedWindows.DEFAULT_WINDOW.value) {
                                return
                            }
                            if (onHMIStatus.hmiLevel == HMILevel.HMI_FULL && onHMIStatus.firstRun) {
                                Timber.i("We're in FULL!!!")
                                setCommands()
                                if (!metadataInitialized) {
                                    performWelcomeShow()
                                    sendPlayAction(context)
                                }
                                //preloadChoices()
                                subscribeToButtons()
                                //sdlManager!!.fileManager.uploadFile(SdlFile(ICON_FILENAME, FileType.GRAPHIC_PNG, R.drawable.app_icon, true), CompletionListener { })
                            }
                            //else if (onHMIStatus.hmiLevel == HMILevel.HMI_NONE) {
                                //sdlManager!!.sendRPC(SetAppIcon(ICON_FILENAME))
                            //}
                        }
                    })
                }

                override fun onDestroy() {
                    ready = false
                    this@SdlService.stopSelf()
                }

                override fun onError(info: String, e: Exception) {}
                override fun managerShouldUpdateLifecycle(language: Language, hmiLanguage: Language): LifecycleConfigurationUpdate? {
                    var isNeedUpdate = false
                    var appName = APP_NAME
                    var ttsName = APP_NAME
                    /*when (language) {
                        Language.ES_MX -> {
                            isNeedUpdate = true
                            ttsName = APP_NAME_ES
                        }
                        Language.FR_CA -> {
                            isNeedUpdate = true
                            ttsName = APP_NAME_FR
                        }
                        else -> {
                        }
                    }
                    when (hmiLanguage) {
                        Language.ES_MX -> {
                            isNeedUpdate = true
                            appName = APP_NAME_ES
                        }
                        Language.FR_CA -> {
                            isNeedUpdate = true
                            appName = APP_NAME_FR
                        }
                        else -> {
                        }
                    }*/
                    return if (isNeedUpdate) {
                        val chunks = Vector(listOf(TTSChunk(ttsName, SpeechCapabilities.TEXT)))
                        LifecycleConfigurationUpdate(appName, null, chunks, null)
                    } else {
                        null
                    }
                }

                override fun onSystemInfoReceived(systemInfo: SystemInfo): Boolean {
                    //Check the SystemInfo object to ensure that the connection to the device should continue
                    return true
                }
            }

            // Create App Icon, this is set in the SdlManager builder
            val appIcon = SdlArtwork(ICON_FILENAME, FileType.GRAPHIC_PNG, R.drawable.app_icon, true)

            // The manager builder sets options for your session
            val builder = SdlManager.Builder(this, APP_ID, APP_NAME, listener)
            builder.setAppTypes(appType)
            builder.setTransportType(transport!!)
            builder.setAppIcon(appIcon)
            sdlManager = builder.build()
            sdlManager?.start()
        }
    }

    private fun onNowPlaying(nowPlaying: Bundle) {
        val metadata = nowPlaying.getParcelable<MediaMetadataCompat>(Constants.SONG_METADATA)
        var artFile : File? = null
        var sdlArtwork : SdlArtwork? = null
        if (metadata.containsKey(MediaMetadataCompat.METADATA_KEY_ART_URI)) {
            artFile = File(metadata.getString(MediaMetadataCompat.METADATA_KEY_ART_URI))
            sdlArtwork = SdlArtwork(artFile.name, FileType.GRAPHIC_JPEG, artFile.toUri(), false)
        }
        if ((sdlArtwork != null) && !sdlManager?.fileManager?.hasUploadedFile(sdlArtwork)!!) {
            sdlManager?.screenManager?.beginTransaction()
            sdlManager?.screenManager?.primaryGraphic = missingArt
            sdlManager?.screenManager?.commit { success ->
                if (success) {
                    Log.i(TAG, "Transition displayed successfully")
                }
                Log.i(TAG, "On Now Playing!!!! " + nowPlaying.getLong(Constants.POSITION))
                showMetadata(metadata, artFile, sdlArtwork, nowPlaying.getInt(Constants.SHUFFLE_MODE), nowPlaying.getInt(Constants.REPEAT_MODE))
            }
        }
        else {
            showMetadata(metadata, artFile, sdlArtwork, nowPlaying.getInt(Constants.SHUFFLE_MODE), nowPlaying.getInt(Constants.REPEAT_MODE))
        }

        var smct = SetMediaClockTimer(
                if (nowPlaying.getBoolean(Constants.PLAYING))
                    UpdateMode.COUNTUP else UpdateMode.PAUSE
        )
        smct.setStartTime(getTime(nowPlaying.getLong(Constants.POSITION)))
        smct.setEndTime(getTime(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)))
        smct.setAudioStreamingIndicator(
                if (nowPlaying.getBoolean(Constants.PLAYING))
                    AudioStreamingIndicator.PAUSE else AudioStreamingIndicator.PLAY
        )
        sdlManager?.sendRPC(smct)
    }

    private fun showMetadata(metadata: MediaMetadataCompat, artFile: File?, sdlArtwork: SdlArtwork?, shuffleMode: Int, repeatMode: Int) {
        metadataInitialized = true
        sdlManager?.screenManager?.beginTransaction()
        sdlManager?.screenManager?.textField1 = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        sdlManager?.screenManager?.textField2 = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
        sdlManager?.screenManager?.textField3 = metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)
        val context = this
        val shuffleArt = when (shuffleMode) {
            PlaybackStateCompat.SHUFFLE_MODE_ALL -> shuffleOnArt
            else -> shuffleOffArt
        }
        val shuffleButton = SoftButtonObject("Shuffle", "Shuffle", shuffleArt, object: OnEventListener {
            override fun onPress(softButtonObject: SoftButtonObject?, onButtonPress: OnButtonPress?) {
                sendToggleShuffleAction(context)
            }

            override fun onEvent(softButtonObject: SoftButtonObject?, onButtonEvent: OnButtonEvent?) {

            }
        })

        val repeatArt = when (repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ALL -> repeatOnArt
            PlaybackStateCompat.REPEAT_MODE_ONE -> repeatOneArt
            else -> repeatOffArt
        }
        val repeatButton = SoftButtonObject("Repeat", "Repeat", repeatArt, object: OnEventListener {
            override fun onPress(softButtonObject: SoftButtonObject?, onButtonPress: OnButtonPress?) {
                sendToggleRepeatAction(context)
            }

            override fun onEvent(softButtonObject: SoftButtonObject?, onButtonEvent: OnButtonEvent?) {

            }
        })
        sdlManager?.screenManager?.softButtonObjects = Arrays.asList(shuffleButton, repeatButton)

        if (artFile != null && sdlArtwork != null && artFile.exists()) {
            sdlManager?.screenManager?.primaryGraphic = sdlArtwork
        }
        sdlManager?.screenManager?.commit { success ->
            if (success) {
                Log.i(TAG, "Set now playing data successfully")
            }
        }
    }

    private fun getTime(timeMs: Long): StartTime {
        val time = StartTime()
        val seconds = (timeMs / 1000).toInt()
        time.setHours(seconds / (60 * 60))
        time.setMinutes((seconds / 60) % 60)
        time.setSeconds(seconds % 60)
        return time
    }

    /**
     * Send some voice commands
     */
    private fun setCommands() {
        //val list1 = listOf("Command One")
        //val list2 = listOf("Command two")
        //val voiceCommand1 = VoiceCommand(list1) { Log.i(TAG, "Voice Command 1 triggered") }
        //val voiceCommand2 = VoiceCommand(list2) { Log.i(TAG, "Voice Command 2 triggered") }
        val context = this
        val menuCells = LinkedList<MenuCell>()

        val allArtists = artistRepository.getAllArtists(null)
        val artistList: List<MenuCell> = allArtists.map {
            val id = it.id
            MenuCell(it.name, it.albumCount.toString() + " Album(s)", null,
                    null, null,
                    Arrays.asList("Play artist " + it.name, "Play all songs by " + it.name),
                    { sendPlayArtistAction(context, id) })
        }
        val artistMenu = MenuCell("Artists", null, null, MenuLayout.LIST,
                null, null,
                artistList)
        menuCells.add(artistMenu)

        val allAlbums = albumRepository.getAllAlbums(null)
        val albumList: List<MenuCell> = allAlbums.map {
            val id = it.id
            MenuCell(it.title, it.songCount.toString() + " Song(s)", null,
                    null, null,
                    Arrays.asList("Play " + it.title + " by " + it.artist),
                    { sendPlayAlbumAction(context, id) })
        }
        val albumMenu = MenuCell("Albums", null, null, MenuLayout.LIST,
                null, null,
                albumList)
        menuCells.add(albumMenu)

        val allPlaylists = playlistRepository.getPlaylists(null).filter { !it.name.startsWith("<Link>") }
        val playlistList: List<MenuCell> = allPlaylists.map {
            val id = it.id
            MenuCell(it.name, it.songCount.toString() + " Song(s)", null,
                    null, null,
                    Arrays.asList("Start playlist " + it.name),
                    { sendPlayPlaylistAction(context, id) })
        }
        val playlistMenu = MenuCell("Playlists", null, null, MenuLayout.LIST,
                null, null,
                playlistList)
        menuCells.add(playlistMenu)

        val allSongsCommand = MenuCell("Shuffle All Songs", null, null,
                shuffleOffArt, null,
                Arrays.asList("Play all", "Play all songs"),
                { sendShuffleAllAction(context) })
        menuCells.add(allSongsCommand)

        sdlManager!!.screenManager.menu = menuCells
    }

    /**
     * Use the Screen Manager to set the initial screen text and set the image.
     * Because we are setting multiple items, we will call beginTransaction() first,
     * and finish with commit() when we are done.
     */
    private fun performWelcomeShow() {
        sdlManager!!.screenManager.beginTransaction()
        sdlManager!!.screenManager.textField1 = APP_NAME
        sdlManager!!.screenManager.textField2 = WELCOME_SHOW
        sdlManager!!.screenManager.primaryGraphic = missingArt
        sdlManager!!.screenManager.changeLayout(TemplateConfiguration("MEDIA"), {
            Log.i(TAG, "template set")
        })
        sdlManager!!.screenManager.commit { success ->
            if (success) {
                Log.i(TAG, "welcome show successful")
            }
        }
    }

    /**
     * Attempts to Subscribe to all preset buttons
     */
    private fun subscribeToButtons() {
        val context = this;
        //val buttonNames = arrayOf(ButtonName.PLAY_PAUSE, ButtonName.SEEKLEFT, ButtonName.SEEKRIGHT, ButtonName.VOLUME_UP, ButtonName.VOLUME_DOWN, ButtonName.SHUFFLE, ButtonName.REPEAT)
        sdlManager!!.screenManager.addButtonListener(ButtonName.PLAY_PAUSE, object : OnButtonListener {
            override fun onPress(buttonName: ButtonName, buttonPress: OnButtonPress) {
                sendPlayPauseAction(context)
            }

            override fun onEvent(buttonName: ButtonName, buttonEvent: OnButtonEvent) {
            }

            override fun onError(info: String) {
                Log.i(TAG, "onError: $info")
            }
        })
        sdlManager!!.screenManager.addButtonListener(ButtonName.SEEKLEFT, object : OnButtonListener {
            override fun onPress(buttonName: ButtonName, buttonPress: OnButtonPress) {
                sendPreviousAction(context)
            }

            override fun onEvent(buttonName: ButtonName, buttonEvent: OnButtonEvent) {
            }

            override fun onError(info: String) {
                Log.i(TAG, "onError: $info")
            }
        })
        sdlManager!!.screenManager.addButtonListener(ButtonName.SEEKRIGHT, object : OnButtonListener {
            override fun onPress(buttonName: ButtonName, buttonPress: OnButtonPress) {
                sendNextAction(context)
            }

            override fun onEvent(buttonName: ButtonName, buttonEvent: OnButtonEvent) {
            }

            override fun onError(info: String) {
                Log.i(TAG, "onError: $info")
            }
        })
    }

    private fun sendShuffleAllAction(context: Context) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_SHUFFLE_ALL
        }
        context.startService(actionIntent)
    }

    private fun sendPlayArtistAction(context: Context, artistID: Long) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_PLAY_ARTIST
        }
        actionIntent.putExtra(ARTIST_ID, artistID)
        context.startService(actionIntent)
    }

    private fun sendPlayAlbumAction(context: Context, albumID: Long) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_PLAY_ALBUM
        }
        actionIntent.putExtra(ALBUM_ID, albumID)
        context.startService(actionIntent)
    }

    private fun sendPlayPlaylistAction(context: Context, playlistID: Long) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_PLAY_PLAYLIST
        }
        actionIntent.putExtra(PLAYLIST_ID, playlistID)
        context.startService(actionIntent)
    }

    private fun sendPlayAction(context: Context) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_PLAY
        }
        context.startService(actionIntent)
    }

    private fun sendPlayPauseAction(context: Context) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_PLAY_PAUSE
        }
        //val shareIntent = Intent.createChooser(actionIntent, null)
        context.startService(actionIntent)
    }

    private fun sendPreviousAction(context: Context) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_PREVIOUS
        }
        context.startService(actionIntent)
    }

    private fun sendNextAction(context: Context) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_NEXT
        }
        context.startService(actionIntent)
    }

    private fun sendToggleShuffleAction(context: Context) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_TOGGLE_SHUFFLE
        }
        context.startService(actionIntent)
    }

    private fun sendToggleRepeatAction(context: Context) {
        val actionIntent = Intent(context, TimberMusicService::class.java).apply {
            action = Constants.ACTION_TOGGLE_REPEAT
        }
        context.startService(actionIntent)
    }

    /**
     * Will show a sample test message on screen as well as speak a sample test message
     */
    private fun showTest() {
        sdlManager!!.screenManager.beginTransaction()
        sdlManager!!.screenManager.textField1 = "Test Cell 1 has been selected"
        sdlManager!!.screenManager.textField2 = ""
        sdlManager!!.screenManager.commit(null)
        val chunks = listOf(TTSChunk(TEST_COMMAND_NAME, SpeechCapabilities.TEXT))
        sdlManager!!.sendRPC(Speak(chunks))
    }

    private fun showAlert(text: String) {
        val builder = AlertView.Builder()
        builder.setText(text)
        builder.setTimeout(5)
        val alertView = builder.build()
        sdlManager!!.screenManager.presentAlert(alertView) { success, tryAgainTime -> Log.i(TAG, "Alert presented: $success") }
    }

    // Choice Set
    private fun preloadChoices() {
        val cell1 = ChoiceCell("Item 1")
        val cell2 = ChoiceCell("Item 2")
        val cell3 = ChoiceCell("Item 3")
        choiceCellList = ArrayList(Arrays.asList(cell1, cell2, cell3))
        sdlManager!!.screenManager.preloadChoices(choiceCellList as ArrayList<ChoiceCell>, null)
    }

    private fun showPerformInteraction() {
        if (choiceCellList != null) {
            val choiceSet = ChoiceSet("Choose an Item from the list", choiceCellList!!, object : ChoiceSetSelectionListener {
                override fun onChoiceSelected(choiceCell: ChoiceCell, triggerSource: TriggerSource, rowIndex: Int) {
                    showAlert(choiceCell.text + " was selected")
                }

                override fun onError(error: String) {
                    Log.e(TAG, "There was an error showing the perform interaction: $error")
                }
            })
            sdlManager!!.screenManager.presentChoiceSet(choiceSet, InteractionMode.MANUAL_ONLY)
        }
    }

    companion object {
        private const val TAG = "SDL Service"
        private const val APP_NAME = "SDL Music"
        //private const val APP_NAME_ES = "Hola Sdl"
        //private const val APP_NAME_FR = "Bonjour Sdl"
        private const val APP_ID = "8678310"
        private const val ICON_FILENAME = "app_icon.png"
        private const val SDL_IMAGE_FILENAME = "sdl_full_image.png"
        private const val WELCOME_SHOW = "Select a playlist"
        private const val WELCOME_SPEAK = "Welcome to Hello S D L"
        private const val TEST_COMMAND_NAME = "Test Command"
        private const val FOREGROUND_SERVICE_ID = 111

        // TCP/IP transport config
        // The default port is 12345
        // The IP is of the machine that is running SDL Core
        private const val TCP_PORT = 12247
        private const val DEV_MACHINE_IP_ADDRESS = "m.sdl.tools"
    }
}
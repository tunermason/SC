package com.mctw.sc

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mctw.sc.Utils.readInternalFile
import com.mctw.sc.Utils.writeInternalFile
import com.mctw.sc.call.RTCCall
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.*

class MainService : Service(), Runnable {
    private val binder = MainBinder()
    private var serverSocket: ServerSocket? = null
    private var database: Database? = null
    var firstStart = false
    private var databasePath = ""
    var databasePassword = ""

    @Volatile
    private var isServerSocketRunning = true
    private var currentCall: RTCCall? = null

    override fun onCreate() {
        super.onCreate()
        databasePath = this.filesDir.toString() + "/database.bin"
        // handle incoming connections
        Thread(this).start()
    }

    private fun updateNotification(text: String) {
        val notification = getNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun getNotification(text: String): Notification {
        val channelId = "meshenger_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "Meshenger Call Listener",
                NotificationManager.IMPORTANCE_LOW // display notification as collapsed by default
            )
            chan.lightColor = Color.RED
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val service = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
        }

        // start MainActivity
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingNotificationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(applicationContext, channelId)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setSmallIcon(R.drawable.ic_logo)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentText(resources.getText(R.string.listen_for_incoming_calls))
            .setContentIntent(pendingNotificationIntent)
            .build()
    }

    private fun showNotification() {
        val notification = getNotification(resources.getText(R.string.listen_for_incoming_calls).toString())
        startForeground(NOTIFICATION_ID, notification)
    }

    fun loadDatabase() {
        if (File(databasePath).exists()) {
            // open existing database
            val db = readInternalFile(databasePath)
            database = Database.fromData(db, databasePassword)
            firstStart = false
        } else {
            // create new database
            database = Database()
            firstStart = true
        }
    }

    fun mergeDatabase(new_db: Database) {
        val oldDatabase = database!!

        oldDatabase.settings = new_db.settings

        for (contact in new_db.contacts.contactList) {
            oldDatabase.contacts.addContact(contact)
        }

        for (event in new_db.events.eventList) {
            oldDatabase.events.addEvent(event)
        }
    }

    fun saveDatabase() {
        try {
            val db = database
            if (db != null) {
                val dbData = Database.toData(db, databasePassword)
                if (dbData != null) {
                    writeInternalFile(databasePath, dbData)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createCommSocket(contact: Contact): Socket? {
        val settings = binder.getSettings()
        val useNeighborTable = settings.useNeighborTable
        val connectTimeout = settings.connectTimeout

        for (address in AddressUtils.getAllSocketAddresses(contact, useNeighborTable)) {
            Log.d(this, "try address: $address")
            val socket = Socket()

            try {
                socket.connect(address, connectTimeout)
                //socket.soTimeout = socketTimeout
                return socket
            } catch (e: SocketTimeoutException) {
                Log.d(this, "SocketTimeoutException: $address")
            } catch (e: ConnectException) {
                // device is online, but does not listen on the given port
                Log.d(this, "ConnectException: $address")
            } catch (e: UnknownHostException) {
                // hostname did not resolve
                Log.d(this, "UnknownHostException: $address")
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }

        return null
    }

    override fun onDestroy() {
        Log.d(this, "onDestroy")
        isServerSocketRunning = false

        // say goodbye
        val database = this.database
        if (database != null && serverSocket != null && serverSocket!!.isBound && !serverSocket!!.isClosed) {
            try {
                val ownPublicKey = database.settings.publicKey
                val ownSecretKey = database.settings.secretKey
                val message = "{\"action\": \"status_change\", \"status\", \"offline\"}"
                for (contact in database.contacts.contactList) {
                    if (contact.state === Contact.State.OFFLINE) {
                        continue
                    }
                    val encrypted = Crypto.encryptMessage(message, contact.publicKey, ownPublicKey, ownSecretKey) ?: continue
                    var socket: Socket? = null
                    try {
                        socket = createCommSocket(contact)
                        if (socket == null) {
                            continue
                        }
                        val pw = PacketWriter(socket)
                        pw.writeMessage(encrypted)
                        socket.close()
                    } catch (e: Exception) {
                        if (socket != null) {
                            try {
                                socket.close()
                            } catch (ee: Exception) {
                                // ignore
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }

        database?.destroy()

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(this, "onStartCommand")

        if (intent == null || intent.action == null) {
            Log.d(this, "Received invalid intent")
        } else if (intent.action == START_FOREGROUND_ACTION) {
            Log.d(this, "Received Start Foreground Intent")
            val notification = getNotification(resources.getText(R.string.listen_for_incoming_calls).toString())
            startForeground(NOTIFICATION_ID, notification)
        } else if (intent.action == STOP_FOREGROUND_ACTION) {
            Log.d(this, "Received Stop Foreground Intent")
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun run() {
        try {
            // wait until database is ready
            while (database == null && isServerSocketRunning) {
                try {
                    Thread.sleep(1000)
                } catch (e: Exception) {
                    break
                }
            }
            serverSocket = ServerSocket(serverPort)
            while (isServerSocketRunning) {
                try {
                    val socket = serverSocket!!.accept()
                    Log.d(this, "new incoming connection")
                    RTCCall.createIncomingCall(binder, socket)
                } catch (e: IOException) {
                    // ignore
                }
            }
        } catch (e: IOException) {
            Log.e(this, "Exit service: $e")
            e.printStackTrace()
            Handler(mainLooper).post { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            stopSelf()
            return
        }
    }

    /*
    * Allows communication between MainService and other objects
    */
    inner class MainBinder : Binder() {
        fun getService(): MainService {
            return this@MainService
        }

        fun isDatabaseLoaded(): Boolean {
            return this@MainService.database != null
        }

        fun getDatabase(): Database {
            if (this@MainService.database == null) {
                Log.e(this, "database is null => try to reload")
                try {
                    // database is null, this should not happen, but
                    // happens anyway, so let's mitigate it for now
                    // => try to reload it
                    this@MainService.loadDatabase()
                } catch (e: Exception) {
                    Log.e(this, "failed to reload database")
                }
            }
            return this@MainService.database!!
        }

        fun getSettings(): Settings {
            return getDatabase().settings
        }

        fun getContacts(): Contacts {
            return getDatabase().contacts
        }

        fun getEvents(): Events {
            return getDatabase().events
        }

        fun getCurrentCall(): RTCCall? {
            return currentCall
        }

        fun setCurrentCall(call: RTCCall?) {
            currentCall = call
        }

        fun addContact(contact: Contact) {
            getDatabase().contacts.addContact(contact)
            saveDatabase()

            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_contact_list"))
            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_event_list"))
        }

        fun deleteContact(publicKey: ByteArray) {
            getDatabase().contacts.deleteContact(publicKey)
            saveDatabase()

            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_contact_list"))
            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_event_list"))
        }

        fun shutdown() {
            this@MainService.stopSelf()
        }

        fun pingContacts() {
            Log.d(this, "pingContacts")
            val settings = getSettings()
            val contactList = getContacts().contactList
            Thread(
                PingRunnable(
                    this@MainService,
                    contactList,
                    settings.publicKey,
                    settings.secretKey
                )
            ).start()
        }

        fun saveDatabase() {
            this@MainService.saveDatabase()
        }

        internal fun addEvent(contact: Contact, type: Event.Type) {
            getEvents().addEvent(contact, type)
            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_event_list"))
        }

        fun clearEvents() {
            getEvents().clearEvents()
            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_event_list"))
        }
    }

    internal inner class PingRunnable(
        var context: Context,
        val contacts: List<Contact>,
        var ownPublicKey: ByteArray,
        var ownSecretKey: ByteArray,
    ) : Runnable {
        private fun pingContact(contact: Contact) : Contact.State {
            val publicKey = contact.publicKey
            val settings = binder.getSettings()
            val useNeighborTable = settings.useNeighborTable
            val connectTimeout = settings.connectTimeout
            var connected = false
            val socket = Socket()

            try {
                // try to connect
                for (address in AddressUtils.getAllSocketAddresses(contact, useNeighborTable)) {
                    try {
                        socket.connect(address, connectTimeout)
                        connected = true
                        break
                    } catch (e: ConnectException) {
                        // target online, but Meshenger not running
                        return Contact.State.PENDING
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                if (!connected) {
                    return Contact.State.OFFLINE
                }

                val pw = PacketWriter(socket)
                val pr = PacketReader(socket)

                Log.d(this, "send ping to ${contact.name}")
                val encrypted = Crypto.encryptMessage(
                    "{\"action\":\"ping\"}",
                    publicKey,
                    ownPublicKey,
                    ownSecretKey
                ) ?: return Contact.State.BROKEN

                pw.writeMessage(encrypted)
                val request = pr.readMessage() ?: return Contact.State.BROKEN
                val decrypted = Crypto.decryptMessage(
                    request,
                    publicKey,
                    ownPublicKey,
                    ownSecretKey
                ) ?: return Contact.State.BROKEN

                val obj = JSONObject(decrypted)
                val action = obj.optString("action", "")
                if (action == "pong") {
                    Log.d(this, "got pong")
                    return Contact.State.ONLINE
                } else {
                    return Contact.State.BROKEN
                }
            } catch (e: Exception) {
                return Contact.State.BROKEN
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }

        override fun run() {
            for (contact in contacts) {
                val state = pingContact(contact)
                // set contact state
                binder.getContacts()
                    .getContactByPublicKey(contact.publicKey)
                    ?.state = state
            }

            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("refresh_contact_list"))
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("refresh_event_list"))
        }

    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    companion object {
        const val serverPort = 10001
        private const val START_FOREGROUND_ACTION = "START_FOREGROUND_ACTION"
        private const val STOP_FOREGROUND_ACTION = "STOP_FOREGROUND_ACTION"
        private const val NOTIFICATION_ID = 42

        fun start(ctx: Context) {
            val startIntent = Intent(ctx, MainService::class.java)
            startIntent.action = START_FOREGROUND_ACTION
            ContextCompat.startForegroundService(ctx, startIntent)
        }

        fun stop(ctx: Context) {
            val stopIntent = Intent(ctx, MainService::class.java)
            stopIntent.action = STOP_FOREGROUND_ACTION
            ctx.startService(stopIntent)
        }
    }
}
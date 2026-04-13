package com.android.systemui.axdynamicbar.data.source

import android.app.Notification
import android.content.Context
import com.android.systemui.res.R
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.util.ScrimUtils
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SysUISingleton
class NotificationIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
) {
    companion object {
        private const val TAG = "NotificationIslandManager"

        private const val SCREEN_RECORD_PACKAGE = "com.android.systemui"
        private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")
        private val ALARM_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")

        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private val SPORTS_PACKAGES = setOf(
            "com.espn.score_center",
            "com.fivemobile.thescore",
            "com.yahoo.mobile.client.android.sportacular",
            "com.sofascore.app",
            "com.fotmob",
            "com.foxsports.android",
            "com.cbs.sports.android",
            "com.bleacherreport.android.teamstream",
            "com.nbaimd.gametime.nba2011",
            "com.nfl.fantasy.core",
            "com.mlb.atbat",
            "com.nhl.gc1112.free",
            "livescore.livesportsmedia.com",
            "com.flashscore.en",
            "com.365scores.android",
            "lunosoftware.sportsalerts",
            "com.onefootball.brasil",
        )

        private const val MIN_SPORTS_ICON_SIZE_PX = 24
        private val SCORE_PATTERN =
            Regex(
                """(.+?)\s+(\d+(?:[./]\d+)?(?:\s*\([^)]+\))?)\s*[-–—:]\s*(\d+(?:[./]\d+)?(?:\s*\([^)]+\))?)\s+(.+)"""
            )
        private val VS_PATTERN =
            Regex("""(.+?)\s+(?:vs\.?|v\.?|at)\s+(.+)""", RegexOption.IGNORE_CASE)
        private val STANDALONE_SCORE_PATTERN =
            Regex("""^\d+(?:[./]\d+)?(?:\s*\([^)]+\))?$""")
        private val CLOCK_TIME_PATTERN =
            Regex("""^\d{1,2}:\d{2}(?:\s*[ap]m)?$""", RegexOption.IGNORE_CASE)
        private val ORDINAL_POSITION_PATTERN =
            Regex("""^\d{1,2}(?:st|nd|rd|th)\s+position$""", RegexOption.IGNORE_CASE)
        private val GENERIC_SPORTS_NOISE =
            listOf(
                "see table",
                "game-day breakdown",
                "get ai-powered insights",
                "stats, and more",
                "powered insights",
                "google",
                "google sports",
            )

        private const val NOW_PLAYING_PACKAGE = "com.google.android.as"
        private const val NOW_PLAYING_CHANNEL = "ambientmusic"

        private val RECORDER_PACKAGES =
            setOf("com.google.android.apps.recorder", "com.android.soundrecorder")

        private val SUPPRESSED_PACKAGES: Set<String> = buildSet {
            addAll(CLOCK_PACKAGES)
            addAll(ALARM_PACKAGES)
            addAll(RECORDER_PACKAGES)
        }
    }

    private val _timerEvent = MutableStateFlow<IslandEvent.Timer?>(null)
    val timerEvent: StateFlow<IslandEvent.Timer?> = _timerEvent.asStateFlow()

    private val _stopwatchEvent = MutableStateFlow<IslandEvent.Stopwatch?>(null)
    val stopwatchEvent: StateFlow<IslandEvent.Stopwatch?> = _stopwatchEvent.asStateFlow()

    private val _alarmEvent = MutableStateFlow<IslandEvent.Alarm?>(null)
    val alarmEvent: StateFlow<IslandEvent.Alarm?> = _alarmEvent.asStateFlow()

    private val _notificationEvents = MutableStateFlow<List<IslandEvent.Notification>>(emptyList())
    val notificationEvents: StateFlow<List<IslandEvent.Notification>> =
        _notificationEvents.asStateFlow()

    private val _audioRecordingEvent = MutableStateFlow<IslandEvent.AudioRecording?>(null)
    val audioRecordingEvent: StateFlow<IslandEvent.AudioRecording?> =
        _audioRecordingEvent.asStateFlow()

    private val _promotedOngoingEvents =
        MutableStateFlow<List<IslandEvent.PromotedOngoing>>(emptyList())
    val promotedOngoingEvents: StateFlow<List<IslandEvent.PromotedOngoing>> =
        _promotedOngoingEvents.asStateFlow()

    private val _sportsEvents = MutableStateFlow<List<IslandEvent.Sports>>(emptyList())
    val sportsEvents: StateFlow<List<IslandEvent.Sports>> = _sportsEvents.asStateFlow()

    private val _nowPlayingEvent = MutableStateFlow<IslandEvent.NowPlaying?>(null)
    val nowPlayingEvent: StateFlow<IslandEvent.NowPlaying?> = _nowPlayingEvent.asStateFlow()

    @Volatile var disabledTypes: Set<String> = emptySet()

    private var recorderPackage: String? = null

    private var recorderNotifKey: String? = null

    private var pauseStartMs: Long = 0L

    private var accumulatedPauseMs: Long = 0L

    val notificationFlow = MutableSharedFlow<IslandEvent.Notification>(extraBufferCapacity = 16)
    val notificationRemovedFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)

    var activeMediaPackageProvider: (() -> String?)? = null

    private val activeNotificationKeys = mutableSetOf<String>()

    private val notifKeyToEventId = mutableMapOf<String, String>()

    private val activeCallKeys = mutableSetOf<String>()

    private val activeAlarmKeys = mutableSetOf<String>()

    var onTimerEvent: ((IslandEvent.Timer) -> Unit)? = null
    var onAlarmEvent: ((IslandEvent.Alarm) -> Unit)? = null
    private data class SportsRemoteContent(
        val textLines: List<String>,
        val images: List<Drawable>,
    )
    var onNotificationPosted: ((IslandEvent.Notification) -> Unit)? = null
    var onScreenRecordNotificationTime: ((Long) -> Unit)? = null

    @Volatile private var listening = false
    @Volatile private var timerJob: Job? = null

    private var timerNotificationKey: String? = null
    private var timerOriginalDurationMs: Long = 0L
    private var stopwatchNotificationKey: String? = null

    private val scrimListener =
        object : ScrimUtils.ScrimEventListener {
            override fun onNotificationRemoved(sbn: StatusBarNotification) {
                val pkg = sbn.packageName ?: return
                val key = sbn.key

                activeNotificationKeys.remove(key)
                activeCallKeys.remove(key)
                activeAlarmKeys.remove(key)

                if (key == timerNotificationKey) {
                    timerNotificationKey = null
                    timerJob?.cancel()
                    timerJob = null
                    _timerEvent.value = null
                }
                if (key == recorderNotifKey) {
                    stopwatchNotificationKey = null
                    _stopwatchEvent.value = null
                }

                if (sbn.key == recorderNotifKey) {
                    recorderNotifKey = null
                    val currentState = _audioRecordingEvent.value?.state
                    if (currentState == RecordingState.SAVED) {
                        recorderPackage = null
                        _audioRecordingEvent.value = null
                        pauseStartMs = 0L
                        accumulatedPauseMs = 0L
                    }
                }

                _promotedOngoingEvents.value =
                    _promotedOngoingEvents.value.filter { it.sbn.key != key }

                _sportsEvents.value =
                    _sportsEvents.value.filter { it.key != key }

                if (_nowPlayingEvent.value?.key == key) {
                    _nowPlayingEvent.value = null
                }

                val removedByKey = _notificationEvents.value.filter { it.sbn.key == key }
                if (removedByKey.isNotEmpty()) {
                    _notificationEvents.value =
                        _notificationEvents.value.filter { it.sbn.key != key }
                    removedByKey.forEach { notificationRemovedFlow.tryEmit(it.id) }
                } else {
                    notificationRemovedFlow.tryEmit(key)
                }

                if (pkg in ALARM_PACKAGES ||
                    sbn.notification?.category == Notification.CATEGORY_ALARM
                ) {
                    if (_alarmEvent.value != null) {
                        _alarmEvent.value = null
                    }
                }

                notifKeyToEventId.remove(key)
            }

            override fun onNotificationPosted(sbn: StatusBarNotification) {
                val pkg = sbn.packageName ?: return
                val extras = sbn.notification?.extras ?: return

                if (
                    pkg == SCREEN_RECORD_PACKAGE &&
                    sbn.isOngoing &&
                    extras.getBoolean("android.showChronometer", false) &&
                    sbn.notification.`when` > 0L
                ) {
                    onScreenRecordNotificationTime?.invoke(sbn.notification.`when`)
                }

                if (pkg in CLOCK_PACKAGES) {
                    val channelId = sbn.notification?.channelId?.lowercase() ?: ""
                    val actionLabels =
                        sbn.notification?.actions?.map { it.title?.toString()?.lowercase() ?: "" }
                            ?: emptyList()
                    val hasLap = actionLabels.any { it.contains("lap") }
                    val isCountDown = extras.getBoolean("android.chronometerCountDown", false)

                    val isStopwatch = channelId.contains("stopwatch") || hasLap
                    val isTimer =
                        !isStopwatch &&
                            (channelId.contains("timer") ||
                                channelId.contains("firing") ||
                                isCountDown ||
                                actionLabels.any { it.contains("+1") || it.contains("add") })

                    if (isStopwatch && "stopwatch" !in disabledTypes) {
                        handleStopwatch(sbn, extras, actionLabels)
                        return
                    }
                    if (isTimer && "timer" !in disabledTypes) {
                        handleTimer(sbn, extras, actionLabels)
                        return
                    }
                    if (isStopwatch || isTimer) return
                }

                val isAlarmCategory = sbn.notification?.category == Notification.CATEGORY_ALARM
                if ((isAlarmCategory || pkg in ALARM_PACKAGES) && sbn.isOngoing) {
                    activeAlarmKeys.add(sbn.key)
                    if ("alarm" !in disabledTypes) handleAlarm(sbn, extras)
                    return
                }

                if (sbn.notification?.category == Notification.CATEGORY_CALL) {
                    val isCallStyle =
                        extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
                            extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
                            extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)
                    if (isCallStyle) {
                        activeCallKeys.add(sbn.key)
                        handleCallNotification(sbn, extras)
                        return
                    }
                }

                val allActions = sbn.notification?.actions ?: emptyArray()
                val isMedia = sbn.notification?.category == Notification.CATEGORY_TRANSPORT ||
                    sbn.notification?.extras?.containsKey(
                        Notification.EXTRA_MEDIA_SESSION
                    ) == true
                if (sbn.isOngoing && !isMedia && "audio_recording" !in disabledTypes) {
                    val recActions =
                        allActions.filter { a ->
                            val lbl = a.title?.toString()?.lowercase() ?: ""
                            lbl.contains("stop") || lbl.contains("pause") || lbl.contains("resume")
                        }
                    val hasStop =
                        recActions.any { a ->
                            (a.title?.toString()?.lowercase() ?: "").contains("stop")
                        }
                    if (hasStop && recActions.size >= 2) {
                        val isPaused =
                            recActions.any { a ->
                                (a.title?.toString()?.lowercase() ?: "").contains("resume")
                            }
                        val notifActions =
                            recActions.mapNotNull { a ->
                                a.title?.let {
                                    IslandEvent.NotificationAction(label = it, action = a)
                                }
                            }
                        val appName = resolveAppName(pkg)
                        val existing = _audioRecordingEvent.value
                        val prevState = existing?.state

                        val startTime =
                            if (
                                sbn.notification?.extras?.getBoolean("android.showChronometer") ==
                                    true
                            )
                                sbn.notification.`when`
                            else existing?.startTimeMs ?: System.currentTimeMillis()

                        val now = System.currentTimeMillis()
                        if (isPaused && prevState != RecordingState.PAUSED) {
                            pauseStartMs = now
                        } else if (
                            !isPaused && prevState == RecordingState.PAUSED
                        ) {
                            accumulatedPauseMs += (now - pauseStartMs).coerceAtLeast(0L)
                            pauseStartMs = 0L
                        }

                        recorderPackage = pkg
                        recorderNotifKey = sbn.key
                        _audioRecordingEvent.value =
                            IslandEvent.AudioRecording(
                                appName = appName,
                                state =
                                    if (isPaused) RecordingState.PAUSED
                                    else RecordingState.RECORDING,
                                startTimeMs = startTime,
                                actions = notifActions,
                                pausedDurationMs = accumulatedPauseMs,
                            )
                        return
                    }
                }

                if (
                    pkg == recorderPackage && _audioRecordingEvent.value != null && !sbn.isOngoing
                ) {
                    val notifActions =
                        allActions.mapNotNull { a ->
                            a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
                        }
                    val title = extras.getString("android.title") ?: ""
                    val existing = _audioRecordingEvent.value ?: return
                    recorderNotifKey = sbn.key
                    _audioRecordingEvent.value =
                        existing.copy(
                            appName = title.ifEmpty { existing.appName },
                            state = RecordingState.SAVED,
                            actions = notifActions,
                        )
                    return
                }

                if (pkg == NOW_PLAYING_PACKAGE) {
                    val channel = sbn.notification?.channelId ?: ""
                    if (channel.contains(NOW_PLAYING_CHANNEL)) {
                        if ("now_playing" !in disabledTypes) handleNowPlaying(sbn, extras)
                        return
                    }
                }

                if (pkg in SUPPRESSED_PACKAGES) return

                if ("sports" !in disabledTypes) {
                    if (pkg == GOOGLE_PACKAGE) {
                        if (shouldAttemptGoogleSportsCapture(sbn, extras) &&
                            handleSportsScore(sbn, extras, forceCapture = true)
                        ) {
                            return
                        }
                    }
                    if (pkg in SPORTS_PACKAGES && handleSportsScore(sbn, extras, forceCapture = true)) return
                }

                if (sbn.isOngoing && isPromotable(sbn, extras)) {
                    if ("promoted_ongoing" !in disabledTypes) handlePromotedOngoing(sbn, extras, pkg)
                    return
                }

                val indeterminate = extras.getBoolean("android.progressIndeterminate", false)
                val progressRaw = extras.getInt("android.progress", -1)
                val progressMax = extras.getInt("android.progressMax", 0)
                val hasProgress =
                    indeterminate ||
                        (extras.containsKey("android.progress") &&
                            progressMax > 0 &&
                            progressRaw >= 0)

                val shouldPromoteOngoing =
                    (sbn.isOngoing && (isPromotable(sbn, extras) || hasProgress)) ||
                        shouldPromoteProgressNotification(sbn, extras, hasProgress)

                if (shouldPromoteOngoing) {
                    clearNotificationStateForKey(sbn.key, notifyRemoval = true)
                    if ("promoted_ongoing" !in disabledTypes) {
                        handlePromotedOngoing(sbn, extras, pkg)
                    } else {
                        clearPromotedOngoing(sbn.key)
                    }
                    return
                }

                clearPromotedOngoing(sbn.key)

                if (sbn.isOngoing) {
                    clearNotificationStateForKey(sbn.key, notifyRemoval = true)
                    return
                }
                if ("notification" in disabledTypes) return
                val category = sbn.notification?.category
                if (category == Notification.CATEGORY_TRANSPORT) return
                if (category == Notification.CATEGORY_SERVICE && !hasProgress) return
                if (sbn.packageName == activeMediaPackageProvider?.invoke()) return

                val notifFlags = sbn.notification?.flags ?: 0
                if (notifFlags and Notification.FLAG_GROUP_SUMMARY != 0) return
                val groupKey = if (sbn.isGroup) sbn.groupKey else null

                val title = extras.getString("android.title")
                val text =
                    extras.getCharSequence("android.bigText")?.toString()?.takeIf {
                        it.isNotEmpty()
                    } ?: extras.getString("android.text")

                val alreadyActive = activeNotificationKeys.contains(sbn.key)
                val existingEvent = _notificationEvents.value.find { it.sbn.key == sbn.key }
                val contentChanged = existingEvent != null && (
                    existingEvent.title != title ||
                    existingEvent.text != text ||
                    existingEvent.progress != (if (hasProgress && !indeterminate) progressRaw else -1)
                )
                if (alreadyActive && !contentChanged) return

                activeNotificationKeys.add(sbn.key)

                val icon =
                    try {
                        context.packageManager.getApplicationIcon(pkg)
                    } catch (_: Exception) {
                        null
                    }
                val appName =
                    try {
                        context.packageManager
                            .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
                            .toString()
                    } catch (_: Exception) {
                        pkg
                    }

                val allNotifActions = sbn.notification?.actions ?: emptyArray()

                val actions =
                    allNotifActions
                        .filter { a -> a.remoteInputs.isNullOrEmpty() && a.actionIntent != null }
                        .take(2)
                        .mapNotNull { a ->
                            a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
                        }

                val replyAction =
                    allNotifActions
                        .firstOrNull { a ->
                            !a.remoteInputs.isNullOrEmpty() && a.actionIntent != null
                        }
                        ?.let { a ->
                            val remoteInput = a.remoteInputs!!.first()
                            IslandEvent.ReplyAction(
                                label = a.title ?: remoteInput.label ?: "Reply",
                                action = a,
                                remoteInput = remoteInput,
                            )
                        }

                var senderIcon: Drawable? = null
                var senderName: String? = null
                var latestMessageText: String? = null
                var isConversation = false
                var isGroupConversation = false
                var conversationTitle: String? = null
                val notif = sbn.notification
                if (
                    notif != null &&
                        notif.isStyle(Notification.MessagingStyle::class.java) &&
                        notif.extras != null
                ) {
                    isConversation = true
                    isGroupConversation = notif.extras.getBoolean(
                        Notification.EXTRA_IS_GROUP_CONVERSATION, false
                    )
                    conversationTitle = notif.extras.getCharSequence(
                        Notification.EXTRA_CONVERSATION_TITLE
                    )?.toString()?.takeIf { it.isNotEmpty() }
                    val messagesArray =
                        notif.extras.getParcelableArray(
                            Notification.EXTRA_MESSAGES,
                            Parcelable::class.java,
                        )
                    if (messagesArray != null && messagesArray.isNotEmpty()) {
                        val messages =
                            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                                messagesArray
                            )
                        val lastMessage = messages.maxByOrNull { it.timestamp }
                        val sender = lastMessage?.senderPerson
                        senderName = sender?.name?.toString()
                        latestMessageText = lastMessage?.text?.toString()
                        senderIcon =
                            try {
                                sender?.icon?.loadDrawable(context)
                            } catch (_: Exception) {
                                null
                            }
                    }

                    if (senderIcon == null) {
                        senderIcon =
                            try {
                                notif.extras
                                    .getParcelable(
                                        Notification.EXTRA_CONVERSATION_ICON,
                                        Icon::class.java,
                                    )
                                    ?.loadDrawable(context)
                            } catch (_: Exception) {
                                null
                            }
                    }

                    if (senderIcon == null) {
                        senderIcon =
                            try {
                                notif.getLargeIcon()?.loadDrawable(context)
                            } catch (_: Exception) {
                                null
                            }
                    }
                }

                val notificationImage = extractNotificationImage(extras, sbn)

                val event =
                    IslandEvent.Notification(
                        sbn = sbn,
                        title = title,
                        text = latestMessageText ?: text,
                        appIcon = icon,
                        appName = appName,
                        progress = if (hasProgress && !indeterminate) progressRaw else -1,
                        progressMax = progressMax.coerceAtLeast(1),
                        isProgressIndeterminate = indeterminate,
                        actions = actions,
                        replyAction = replyAction,
                        isConversation = isConversation,
                        isGroupConversation = isGroupConversation,
                        conversationTitle = conversationTitle,
                        senderIcon = senderIcon,
                        senderName = senderName,
                        groupKey = groupKey,
                        notificationImage = notificationImage,
                    )
                notifKeyToEventId[sbn.key] = event.id
                applicationScope.launch { notificationFlow.emit(event) }
                onNotificationPosted?.invoke(event)
            }
        }

    fun startListening() {
        if (listening) return
        listening = true
        ScrimUtils.get().addListener(scrimListener)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        ScrimUtils.get().removeListener(scrimListener)
        activeNotificationKeys.clear()
        activeCallKeys.clear()
        activeAlarmKeys.clear()
        notifKeyToEventId.clear()
        timerJob?.cancel()
        timerJob = null
        _timerEvent.value = null
        _stopwatchEvent.value = null
        _alarmEvent.value = null
        _notificationEvents.value = emptyList()
        _promotedOngoingEvents.value = emptyList()
        _sportsEvents.value = emptyList()
        _nowPlayingEvent.value = null
        _audioRecordingEvent.value = null
        recorderPackage = null
        recorderNotifKey = null
        pauseStartMs = 0L
        accumulatedPauseMs = 0L
    }

    fun clearAudioRecording() {
        _audioRecordingEvent.value = null
        recorderPackage = null
        recorderNotifKey = null
        pauseStartMs = 0L
        accumulatedPauseMs = 0L
    }

    fun dismissNotification(event: IslandEvent.Notification) {
        _notificationEvents.value = _notificationEvents.value.filter { it.id != event.id }
        val key = event.sbn.key
        activeNotificationKeys.remove(key)
        notifKeyToEventId.remove(key)
    }

    fun coalesceNotification(event: IslandEvent.Notification) {
        val current = _notificationEvents.value.toMutableList()
        current.removeAll { it.id == event.id || it.sbn.key == event.sbn.key }
        current.add(0, event)
        _notificationEvents.value = current
        notifKeyToEventId[event.sbn.key] = event.id
    }

    private fun clearNotificationStateForKey(
        key: String,
        notifyRemoval: Boolean = false,
    ) {
        val removedByKey = _notificationEvents.value.filter { it.sbn.key == key }
        if (removedByKey.isNotEmpty()) {
            _notificationEvents.value =
                _notificationEvents.value.filter { it.sbn.key != key }
            if (notifyRemoval) {
                removedByKey.forEach { notificationRemovedFlow.tryEmit(it.id) }
            }
        } else if (notifyRemoval) {
            notificationRemovedFlow.tryEmit(key)
        }
        activeNotificationKeys.remove(key)
        notifKeyToEventId.remove(key)
    }

    fun clearTimer() {
        _timerEvent.value = null
        timerOriginalDurationMs = 0L
    }

    fun clearStopwatch() {
        _stopwatchEvent.value = null
    }

    fun clearAlarm() {
        _alarmEvent.value = null
    }

    private fun handleTimer(
        sbn: StatusBarNotification,
        extras: Bundle,
        actionLabels: List<String> = emptyList(),
    ) {
        val label = extras.getString("android.title") ?: context.getString(R.string.ax_dynamic_bar_timer)
        val icon =
            try {
                context.packageManager.getApplicationIcon(sbn.packageName)
            } catch (_: Exception) {
                null
            }

        val hasPauseAction = actionLabels.any { it.contains("pause") }
        val hasResumeAction = actionLabels.any {
            it.contains("resume") || it.contains("play") ||
                (it.contains("start") && !it.contains("stop"))
        }
        val isPaused = hasResumeAction || (actionLabels.isNotEmpty() && !hasPauseAction)

        var endTimeMs = sbn.notification.`when`
        if (endTimeMs <= System.currentTimeMillis()) {
            val chronoBase = extractChronometerBase(sbn)
            if (chronoBase > 0L) {
                val remaining = chronoBase - SystemClock.elapsedRealtime()
                endTimeMs = if (remaining > 0L) System.currentTimeMillis() + remaining else 0L
            } else {
                endTimeMs = 0L
            }
        }

        if (isPaused && endTimeMs > System.currentTimeMillis()) {
            val existing = _timerEvent.value
            if (existing != null && existing.endTimeMs > 0L) {
                endTimeMs = existing.endTimeMs
            }
        }

        val actions = extractNotificationActions(sbn)
        val isNewTimer = timerNotificationKey != sbn.key
        if (isNewTimer && endTimeMs > 0L) {
            timerOriginalDurationMs = (endTimeMs - System.currentTimeMillis()).coerceAtLeast(1000L)
        }
        val event =
            IslandEvent.Timer(
                label = label,
                endTimeMs = endTimeMs,
                originalDurationMs = timerOriginalDurationMs,
                appIcon = icon,
                isPaused = isPaused,
                actions = actions,
            )
        timerNotificationKey = sbn.key
        _timerEvent.value = event
        onTimerEvent?.invoke(event)

        timerJob?.cancel()
        if (endTimeMs > 0L && !isPaused) {
            val remainingMs = endTimeMs - System.currentTimeMillis()
            timerJob =
                applicationScope.launch {
                    delay((remainingMs + 3_000L).coerceAtLeast(3_000L))
                    _timerEvent.value = null
                }
        }
    }

    private fun handleStopwatch(
        sbn: StatusBarNotification,
        extras: Bundle,
        actionLabels: List<String> = emptyList(),
    ) {
        val label = extras.getString("android.title") ?: ""
        val icon =
            try {
                context.packageManager.getApplicationIcon(sbn.packageName)
            } catch (_: Exception) {
                null
            }

        val isRunning = actionLabels.any { it.contains("pause") || it.contains("lap") }

        var startTimeMs = System.currentTimeMillis()
        val chronoBase = extractChronometerBase(sbn)
        if (chronoBase > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - chronoBase
            if (elapsed > 0L) startTimeMs = System.currentTimeMillis() - elapsed
        }

        val actions = extractNotificationActions(sbn)
        val event =
            IslandEvent.Stopwatch(
                label = label,
                startTimeMs = startTimeMs,
                isRunning = isRunning,
                appIcon = icon,
                actions = actions,
            )
        stopwatchNotificationKey = sbn.key
        _stopwatchEvent.value = event
    }

    private fun extractChronometerBase(sbn: StatusBarNotification): Long {
        try {
            val rv = sbn.notification.contentView ?: sbn.notification.bigContentView ?: return 0L
            val pkgCtx = context.createPackageContext(sbn.packageName, Context.CONTEXT_RESTRICTED)
            val container = FrameLayout(context)
            val inflated = rv.apply(pkgCtx, container) ?: return 0L
            return findChronometer(inflated)?.base ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract chronometer base from ${sbn.packageName}", e)
            return 0L
        }
    }

    private fun findChronometer(view: View): Chronometer? {
        if (view is Chronometer) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findChronometer(view.getChildAt(i))?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun extractNotificationActions(
        sbn: StatusBarNotification
    ): List<IslandEvent.NotificationAction> {
        return sbn.notification
            ?.actions
            ?.filter { a -> a.remoteInputs.isNullOrEmpty() && a.actionIntent != null }
            ?.take(3)
            ?.mapNotNull { a ->
                a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
            } ?: emptyList()
    }

    private fun handleAlarm(sbn: StatusBarNotification, extras: Bundle) {
        val label = extras.getString("android.title") ?: context.getString(R.string.ax_dynamic_bar_alarm)
        val isRinging = sbn.notification.category == Notification.CATEGORY_ALARM
        val icon =
            try {
                context.packageManager.getApplicationIcon(sbn.packageName)
            } catch (_: Exception) {
                null
            }
        val event =
            IslandEvent.Alarm(
                label = label,
                triggerTimeMs = sbn.notification.`when`,
                isRinging = isRinging,
                appIcon = icon,
            )
        _alarmEvent.value = event
        onAlarmEvent?.invoke(event)
    }

    private fun resolveAppName(packageName: String): String =
        try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            ""
        }

    private fun handleCallNotification(sbn: StatusBarNotification, extras: Bundle) {
        val callerName = extras.getString("android.title")
        val number = extras.getString("android.text")
        val callerPhoto =
            try {
                sbn.notification?.getLargeIcon()?.loadDrawable(context)
            } catch (_: Exception) {
                null
            }
        val allActions = sbn.notification?.actions ?: emptyArray()
        val actions =
            allActions
                .filter { a -> a.remoteInputs.isNullOrEmpty() && a.actionIntent != null }
                .take(3)
                .mapNotNull { a ->
                    a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
                }

        val androidCallType = extras.getInt(
            Notification.EXTRA_CALL_TYPE,
            Notification.CallStyle.CALL_TYPE_UNKNOWN,
        )
        val callType =
            if (androidCallType == Notification.CallStyle.CALL_TYPE_INCOMING)
                "Phone:incoming"
            else "Phone:active"

        val icon =
            try {
                context.packageManager.getApplicationIcon(sbn.packageName)
            } catch (_: Exception) {
                null
            }

        val callWhen = sbn.notification?.`when` ?: 0L
        val callStart = if (callWhen > 0L) callWhen else System.currentTimeMillis()

        val event =
            IslandEvent.Notification(
                sbn = sbn,
                title = callerName,
                text = number,
                appIcon = icon,
                appName = callType,
                actions = actions,
                senderIcon = callerPhoto,
                senderName = callerName,
                isConversation = false,
                callStartTimeMs = callStart,
            )
        notifKeyToEventId[sbn.key] = event.id
        applicationScope.launch { notificationFlow.emit(event) }
        onNotificationPosted?.invoke(event)
    }

    private fun isPromotable(sbn: StatusBarNotification, extras: Bundle): Boolean {
        val notification = sbn.notification ?: return false

        if (notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0) return true

        return notification.hasPromotableCharacteristics()
    }

    private fun shouldPromoteProgressNotification(
        sbn: StatusBarNotification,
        extras: Bundle,
        hasProgress: Boolean,
    ): Boolean {
        if (!hasProgress || sbn.isOngoing) return false

        val notification = sbn.notification ?: return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        val progressRaw = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        val category = notification.category
        if (category == Notification.CATEGORY_TRANSPORT) return false
        if (category == Notification.CATEGORY_CALL) return false
        if (category == Notification.CATEGORY_MESSAGE) return false
        if (sbn.packageName == activeMediaPackageProvider?.invoke()) return false

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        if (
            isCompletedProgressNotification(
                title = title,
                text = text,
                bigText = bigText,
                progressRaw = progressRaw,
                progressMax = progressMax,
                indeterminate = indeterminate,
            )
        ) {
            return false
        }

        return title.isNotEmpty() || text.isNotEmpty() || bigText.isNotEmpty()
    }

    private fun isCompletedProgressNotification(
        title: String,
        text: String,
        bigText: String,
        progressRaw: Int,
        progressMax: Int,
        indeterminate: Boolean,
    ): Boolean {
        if (!indeterminate && progressMax > 0 && progressRaw >= progressMax) {
            return true
        }

        val combinedText = listOf(title, text, bigText)
            .joinToString(" ")
            .lowercase()

        val zeroTimeRemaining =
            Regex("""\b0\s*(seconds?|secs?|minutes?|mins?)\s+left\b""").containsMatchIn(
                combinedText
            )
        val completionText =
            listOf(
                "complete",
                "completed",
                "download complete",
                "install complete",
                "downloaded",
                "installed",
                "finished",
                "done",
                "ready to open",
            ).any { phrase -> phrase in combinedText }

        if (completionText) {
            return true
        }

        if (!indeterminate && progressMax > 0 && progressRaw >= (progressMax - 1)) {
            if (zeroTimeRemaining) return true
        }

        return false
    }

    private fun handlePromotedOngoing(sbn: StatusBarNotification, extras: Bundle, pkg: String) {
        val shortCritical =
            try {
                sbn.notification?.shortCriticalText?.toString() ?: ""
            } catch (_: Exception) {
                extras.getString("android.shortCriticalText") ?: ""
            }
        val title = extras.getString("android.title") ?: ""
        val text = extras.getString("android.text") ?: ""
        val appName = resolveAppName(pkg)
        val icon = loadNotificationIcon(sbn, pkg)

        val actions = extractNotificationActions(sbn)

        val progressRaw = extras.getInt("android.progress", -1)
        val progressMax = extras.getInt("android.progressMax", 0)
        val indeterminate = extras.getBoolean("android.progressIndeterminate", false)
        val progress =
            if (progressRaw >= 0 && progressMax > 0)
                (progressRaw.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f)
            else -1f

        val event =
            IslandEvent.PromotedOngoing(
                shortText = shortCritical,
                title = title,
                text = text,
                appName = appName,
                appIcon = icon,
                sbn = sbn,
                actions = actions,
                progress = progress,
                isIndeterminate = indeterminate,
            )

        val current = _promotedOngoingEvents.value.toMutableList()
        current.removeAll { it.sbn.key == sbn.key }
        current.add(0, event)
        _promotedOngoingEvents.value = current
    }

    fun clearPromotedOngoing(key: String) {
        _promotedOngoingEvents.value = _promotedOngoingEvents.value.filter { it.sbn.key != key }
    }

    fun clearSportsEvent(key: String) {
        _sportsEvents.value = _sportsEvents.value.filter { it.key != key }
    }

    fun clearNowPlaying() {
        _nowPlayingEvent.value = null
    }

    private fun handleNowPlaying(sbn: StatusBarNotification, extras: Bundle) {
        val title = extras.getCharSequence("android.title")?.toString() ?: return
        val byMatch = Regex("""(.+?)\s+by\s+(.+)""", RegexOption.IGNORE_CASE).find(title)
        val dashParts = if (byMatch == null) title.split(" - ", " – ", limit = 2) else null
        val songTitle: String
        val artist: String
        when {
            byMatch != null -> {
                songTitle = byMatch.groupValues[1].trim()
                artist = byMatch.groupValues[2].trim()
            }
            dashParts != null && dashParts.size == 2 -> {
                songTitle = dashParts[0].trim()
                artist = dashParts[1].trim()
            }
            else -> {
                songTitle = title
                artist = ""
            }
        }

        val allActions = sbn.notification?.actions ?: emptyArray()
        val notifActions = allActions.mapNotNull { a ->
            a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
        }
        val appIcon = loadNotificationIcon(sbn, sbn.packageName)

        _nowPlayingEvent.value = IslandEvent.NowPlaying(
            songTitle = songTitle,
            artist = artist,
            key = sbn.key,
            sbn = sbn,
            appIcon = appIcon,
            actions = notifActions,
        )
    }

    private fun handleSportsScore(
        sbn: StatusBarNotification,
        extras: Bundle,
        forceCapture: Boolean = false,
    ): Boolean {
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val remoteContent = extractSportsRemoteContent(sbn)
        val allFields =
            buildList {
                add(title)
                add(text)
                add(bigText)
                add(subText)
                addAll(remoteContent?.textLines.orEmpty().map { it.trim() })
            }.map { normalizeSportsField(it) }
                .filter { it.isNotEmpty() }
                .filterNot { isGenericSportsNoise(it) }
                .distinct()

        if (allFields.isEmpty()) {
            clearSportsEvent(sbn.key)
            return false
        }

        val scoreMatch = allFields.firstNotNullOfOrNull { SCORE_PATTERN.find(it) }

        var team1Name: String
        var team2Name: String
        var score1 = ""
        var score2 = ""

        if (scoreMatch != null) {
            team1Name = scoreMatch.groupValues[1].trim()
            score1 = scoreMatch.groupValues[2]
            score2 = scoreMatch.groupValues[3]
            team2Name = scoreMatch.groupValues[4].trim()
                .replace(Regex("""\s*[·•|].*"""), "")
            if (!looksLikeSportsTeamName(team1Name) || !looksLikeSportsTeamName(team2Name)) {
                clearSportsEvent(sbn.key)
                return false
            }
        } else {
            val matchup =
                allFields.firstNotNullOfOrNull { extractMatchupFromField(it) }
                    ?: extractMatchupFromField(title)
                    ?: extractMatchupFromField(text)
                    ?: extractMatchupFromField(bigText)
            if (matchup != null) {
                team1Name = matchup.first
                team2Name = matchup.second
                val standaloneScores =
                    allFields.mapNotNull { line ->
                        normalizeSportsField(line).takeIf { STANDALONE_SCORE_PATTERN.matches(it) }
                    }
                if (standaloneScores.size >= 2) {
                    score1 = standaloneScores[0]
                    score2 = standaloneScores[1]
                }
            } else {
                clearSportsEvent(sbn.key)
                return false
            }
        }

        val loweredFields = allFields.map { it.lowercase() }
        val status = when {
            loweredFields.any { it.contains("halftime") || it.contains("half-time") } ->
                IslandEvent.GameStatus.HALFTIME
            loweredFields.any {
                it.contains("final") ||
                    it.contains("full time") ||
                    it == "ft"
            } -> IslandEvent.GameStatus.FINAL
            sbn.isOngoing || loweredFields.any {
                it.contains("live") ||
                    it.contains("in progress") ||
                    Regex("""\bq[1-4]\b""").containsMatchIn(it) ||
                    it.contains("innings") ||
                    it.contains("over")
            } -> IslandEvent.GameStatus.LIVE
            score1.isBlank() && score2.isBlank() -> IslandEvent.GameStatus.PRE_GAME
            else -> IslandEvent.GameStatus.FINAL
        }

        var statusDetail = ""
        if (score1.isNotEmpty()) {
            val shortCritical = try {
                sbn.notification?.shortCriticalText?.toString() ?: ""
            } catch (_: Exception) {
                extras.getString("android.shortCriticalText") ?: ""
            }
            statusDetail = shortCritical
                .replace(score1, "").replace(score2, "")
                .replace("-", "").replace("–", "").replace(":", "")
                .trim()
        }
        if (statusDetail.isBlank()) {
            statusDetail =
                allFields.firstOrNull { field ->
                    field.contains("live", ignoreCase = true) ||
                        field.contains("final", ignoreCase = true) ||
                        field.contains("innings", ignoreCase = true) ||
                        field.contains("quarter", ignoreCase = true) ||
                        field.contains("half", ignoreCase = true) ||
                        field.contains("over", ignoreCase = true)
                }.orEmpty()
        }
        statusDetail = normalizeSportsField(statusDetail)

        val league = allFields.flatMap { field -> field.split("·", "•", "|") }
            .map { it.trim() }
            .firstOrNull { part ->
                part.length in 2..30 &&
                    !part.any { it.isDigit() } &&
                    part != team1Name &&
                    part != team2Name &&
                    !isGenericSportsNoise(part) &&
                    !CLOCK_TIME_PATTERN.matches(part) &&
                    !part.contains("vs", ignoreCase = true) &&
                    !part.contains(" at ", ignoreCase = true)
            } ?: ""

        val commentary =
            allFields.firstOrNull { field ->
                val normalized = normalizeSportsField(field)
                normalized.isNotEmpty() &&
                    normalized != title &&
                    normalized != text &&
                    normalized != bigText &&
                    normalized != subText &&
                    normalized != league &&
                    normalized != statusDetail &&
                    normalized != team1Name &&
                    normalized != team2Name &&
                    !STANDALONE_SCORE_PATTERN.matches(normalized) &&
                    !SCORE_PATTERN.containsMatchIn(normalized) &&
                    !VS_PATTERN.containsMatchIn(normalized) &&
                    !isGenericSportsNoise(normalized) &&
                    !CLOCK_TIME_PATTERN.matches(normalized)
            }.orEmpty()

        val appIcon = loadNotificationIcon(sbn, sbn.packageName)
        val (team1Icon, team2Icon) = selectSportsTeamIcons(remoteContent, appIcon)

        val event = IslandEvent.Sports(
            team1Name = team1Name,
            team2Name = team2Name,
            score1 = score1,
            score2 = score2,
            team1Icon = team1Icon,
            team2Icon = team2Icon,
            status = status,
            statusDetail = statusDetail,
            league = league,
            commentary = commentary,
            key = sbn.key,
            sbn = sbn,
            appIcon = appIcon,
        )

        val current = _sportsEvents.value.toMutableList()
        current.removeAll { it.key == sbn.key }
        current.add(0, event)
        _sportsEvents.value = current
        return true
    }

    private fun normalizeSportsField(value: String): String {
        return value.replace(Regex("""\s+"""), " ").trim().trim('·', '•', '|', '-', '–', '—')
    }

    private fun extractMatchupFromField(value: String): Pair<String, String>? {
        val normalized = normalizeSportsField(value)
        val match = VS_PATTERN.find(normalized) ?: return null
        val first = normalizeSportsField(match.groupValues[1])
        val second = normalizeSportsField(match.groupValues[2])
        return if (looksLikeSportsTeamName(first) && looksLikeSportsTeamName(second)) {
            first to second
        } else {
            null
        }
    }

    private fun looksLikeSportsTeamName(value: String): Boolean {
        val normalized = normalizeSportsField(value)
        if (normalized.isEmpty()) return false
        if (normalized.length !in 2..40) return false
        if (STANDALONE_SCORE_PATTERN.matches(normalized)) return false
        if (CLOCK_TIME_PATTERN.matches(normalized)) return false
        if (ORDINAL_POSITION_PATTERN.matches(normalized)) return false

        val lower = normalized.lowercase()
        if (
            isGenericSportsNoise(normalized) ||
                lower.contains("position") ||
                lower.contains("see table") ||
                lower.contains("insights") ||
                lower.contains("powered") ||
                lower.contains("download") ||
                lower.contains("google")
        ) {
            return false
        }

        val tokens = normalized.split(Regex("""\s+"""))
        if (tokens.size > 4) return false

        val compact = normalized.replace(Regex("""[^A-Za-z0-9]"""), "")
        if (compact.length < 2) return false
        if (compact.count { it.isDigit() } > 2) return false

        return true
    }

    private fun isGenericSportsNoise(value: String): Boolean {
        val normalized = normalizeSportsField(value)
        if (normalized.isEmpty()) return true
        val lower = normalized.lowercase()
        if (CLOCK_TIME_PATTERN.matches(normalized)) return true
        if (ORDINAL_POSITION_PATTERN.matches(normalized)) return true
        if (GENERIC_SPORTS_NOISE.any { it in lower }) return true
        if (lower == "." || lower == "·") return true
        return false
    }

    private fun shouldAttemptGoogleSportsCapture(
        sbn: StatusBarNotification,
        extras: Bundle,
    ): Boolean {
        val groupKey = sbn.groupKey.orEmpty()
        if (groupKey.contains("::sports", ignoreCase = true)) return true

        val candidates =
            listOf(
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            ).map { it.orEmpty().trim() }
                .filter { it.isNotEmpty() }

        if (candidates.any { SCORE_PATTERN.containsMatchIn(it) || VS_PATTERN.containsMatchIn(it) }) {
            return true
        }

        return candidates.any { text ->
            text.contains("live", ignoreCase = true) ||
                text.contains("final", ignoreCase = true) ||
                text.contains("innings", ignoreCase = true) ||
                text.contains("score", ignoreCase = true)
        }
    }

    private fun extractSportsRemoteContent(sbn: StatusBarNotification): SportsRemoteContent? {
        return try {
            val rv = resolveNotificationRemoteViews(sbn.notification) ?: return null
            val pkgCtx = context.createPackageContext(sbn.packageName, Context.CONTEXT_RESTRICTED)
            val container = FrameLayout(context)
            val inflated = rv.apply(pkgCtx, container) ?: return null
            SportsRemoteContent(
                textLines = collectVisibleText(inflated),
                images = collectVisibleImages(inflated),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract sports remote content from ${sbn.packageName}", e)
            null
        }
    }

    private fun resolveNotificationRemoteViews(notification: Notification): RemoteViews? {
        notification.bigContentView?.let { return it }
        notification.contentView?.let { return it }
        return try {
            val builder = Notification.Builder.recoverBuilder(context, notification)
            builder.createBigContentView() ?: builder.createContentView()
        } catch (_: Exception) {
            null
        }
    }

    private fun collectVisibleText(view: View): List<String> {
        val textLines = mutableListOf<String>()
        collectVisibleText(view, textLines)
        return textLines.distinct()
    }

    private fun collectVisibleText(view: View, textLines: MutableList<String>) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView) {
            val value = view.text?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) {
                textLines += value
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectVisibleText(view.getChildAt(i), textLines)
            }
        }
    }

    private fun collectVisibleImages(view: View): List<Drawable> {
        val images = mutableListOf<Drawable>()
        collectVisibleImages(view, images)
        return images
    }

    private fun collectVisibleImages(view: View, images: MutableList<Drawable>) {
        if (view.visibility != View.VISIBLE) return
        if (view is ImageView) {
            val drawable = view.drawable
            if (drawable != null) {
                val width = maxOf(drawable.intrinsicWidth, view.width)
                val height = maxOf(drawable.intrinsicHeight, view.height)
                if (width >= MIN_SPORTS_ICON_SIZE_PX && height >= MIN_SPORTS_ICON_SIZE_PX) {
                    images += cloneDrawable(drawable)
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectVisibleImages(view.getChildAt(i), images)
            }
        }
    }

    private fun selectSportsTeamIcons(
        remoteContent: SportsRemoteContent?,
        appIcon: Drawable?,
    ): Pair<Drawable?, Drawable?> {
        val candidates =
            remoteContent?.images.orEmpty()
                .filterNot { candidate -> appIcon != null && drawablesMatch(candidate, appIcon) }
                .distinctBy { drawableIdentityKey(it) }

        return when {
            candidates.size >= 2 -> candidates[0] to candidates[1]
            candidates.size == 1 -> candidates[0] to null
            else -> null to null
        }
    }

    private fun cloneDrawable(drawable: Drawable): Drawable {
        return drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable.mutate()
    }

    private fun drawablesMatch(first: Drawable, second: Drawable): Boolean {
        if (first === second) return true
        val firstState = first.constantState
        val secondState = second.constantState
        return firstState != null && secondState != null && firstState == secondState
    }

    private fun drawableIdentityKey(drawable: Drawable): String {
        return drawable.constantState?.toString()
            ?: "${drawable.intrinsicWidth}x${drawable.intrinsicHeight}:${drawable.javaClass.name}"
    }

    private fun extractNotificationImage(extras: Bundle, sbn: StatusBarNotification): Drawable? {
        try {
            extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
                ?.loadDrawable(context)?.let { return it }
        } catch (_: Exception) {}

        try {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
                ?.let { return BitmapDrawable(context.resources, it) }
        } catch (_: Exception) {}

        if (!sbn.notification.isStyle(Notification.MessagingStyle::class.java)) {
            try {
                sbn.notification?.getLargeIcon()?.loadDrawable(context)?.let { return it }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun loadNotificationIcon(sbn: StatusBarNotification, pkg: String): Drawable? {
        return try {
            sbn.notification?.smallIcon?.loadDrawable(context)
        } catch (_: Exception) {
            null
        }
            ?: try {
                context.packageManager.getApplicationIcon(pkg)
            } catch (_: Exception) {
                null
            }
    }
}

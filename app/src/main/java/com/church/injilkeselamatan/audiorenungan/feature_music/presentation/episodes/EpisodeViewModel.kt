package com.church.injilkeselamatan.audiorenungan.feature_music.presentation.episodes

import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.*
import com.church.injilkeselamatan.audiorenungan.feature_music.data.util.Resource
import com.church.injilkeselamatan.audiorenungan.feature_music.domain.model.Song
import com.church.injilkeselamatan.audiorenungan.feature_music.domain.use_case.SongUseCases
import com.church.injilkeselamatan.audiorenungan.feature_music.exoplayer.common.MusicServiceConnection
import com.church.injilkeselamatan.audiorenungan.feature_music.exoplayer.download.DownloadListener
import com.church.injilkeselamatan.audiorenungan.feature_music.exoplayer.media.extensions.id
import com.church.injilkeselamatan.audiorenungan.feature_music.exoplayer.media.extensions.isPlayEnabled
import com.church.injilkeselamatan.audiorenungan.feature_music.exoplayer.media.extensions.isPlaying
import com.church.injilkeselamatan.audiorenungan.feature_music.exoplayer.media.extensions.isPrepared
import com.church.injilkeselamatan.audiorenungan.feature_music.presentation.util.SongsState
import com.google.android.exoplayer2.offline.Download
import com.google.android.exoplayer2.offline.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EpisodeViewModel @Inject constructor(
    private val musicServiceConnection: MusicServiceConnection,
    private val songUseCases: SongUseCases,
    private val downloadManager: DownloadManager,
    private val downloadListener: DownloadListener,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    var downloadedLength = MutableLiveData(0f)
    var maxProgress = MutableLiveData(0f)

    private val _state = mutableStateOf(SongsState())
    val state: State<SongsState> = _state

    private val _downloadState = mutableStateOf(SongsState())
    val downloadState: State<SongsState> = _downloadState

    private var loadEpisodeJob: Job? = null
    private var downloadedJob: Job? = null
    private var downloadingJob: Job? = null

    var currentSelectedAlbum: String? = null

    init {
        savedStateHandle.get<String>("album")?.let { album ->
            currentSelectedAlbum = album
        }
        loadEpisodes()
        loadDownloadedEpisodes()
        onDownloadEvent()
    }

    private fun loadEpisodes() {
        loadEpisodeJob?.cancel()
        loadEpisodeJob = songUseCases.getSongs(currentSelectedAlbum).onEach { resource ->
            when (resource) {
                is Resource.Success -> {
                    resource.data?.let { episodes ->
                        _state.value = state.value.copy(
                            songs = episodes,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
                is Resource.Loading -> {
                    _state.value = state.value.copy(
                        isLoading = true,
                        errorMessage = null
                    )
                }
                is Resource.Error -> {
                    _state.value = state.value.copy(
                        isLoading = false,
                        errorMessage = resource.message
                    )
                }
            }
        }.launchIn(viewModelScope)
    }

    fun complatedDownload(): LiveData<Download> {
        return downloadListener.downloadComplated
    }

    fun loadDownloadedEpisodes() {
        downloadedJob?.cancel()
        downloadedJob = songUseCases.getDownloadedSongs(currentSelectedAlbum).onEach { resource ->
            when (resource) {
                is Resource.Success -> {
                    resource.data?.let { episodes ->
                        _downloadState.value = downloadState.value.copy(
                            songs = episodes,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
                is Resource.Loading -> {
                    _state.value = state.value.copy(
                        isLoading = true,
                        errorMessage = null
                    )
                }
                is Resource.Error -> {
                    _state.value = state.value.copy(
                        isLoading = false,
                        errorMessage = resource.message
                    )
                }
            }
        }.launchIn(viewModelScope)
    }

    fun onEvent(event: EpisodesEvent) {
        when (event) {
            is EpisodesEvent.DownloadEpisode -> {
                downloadSong(event.song.id)
                onDownloadEvent()
            }
            is EpisodesEvent.PlayToogle -> {
                playMediaId(event.episode.id)
            }
        }
    }

    private fun onDownloadEvent() {
        downloadingJob?.cancel()
        downloadingJob = viewModelScope.launch {
            try {
                while (true) {
                    delay(100L)
                    val download = downloadManager.currentDownloads[0]
                    maxProgress.postValue(download.contentLength.toFloat())
                    downloadedLength.postValue(download.bytesDownloaded.toFloat())
                }
            } catch (e: IndexOutOfBoundsException) {
                downloadingJob?.cancel()
            }
        }
    }

    private var countedOnState = 0

    fun onState(songId: String): Int? {
        countedOnState++
        Log.d(TAG, countedOnState.toString())
        return downloadManager.currentDownloads.find { download ->
            download.request.id == songId
        }?.state
    }

    private fun playMedia(mediaItem: Song, pauseAllowed: Boolean = true) {
        val nowPlaying = musicServiceConnection.nowPlaying.value

        val transportControls = musicServiceConnection.transportControls
        val isPrepared = musicServiceConnection.playbackState.value?.isPrepared ?: false
        if (isPrepared && mediaItem.id == nowPlaying?.id) {
            musicServiceConnection.playbackState.value?.let { playbackState ->
                when {
                    playbackState.isPlaying ->
                        if (pauseAllowed) transportControls.pause() else Unit
                    playbackState.isPlayEnabled -> transportControls.play()
                    else -> {
                        // Something wrong
                    }
                }
            }
        } else {
            transportControls.playFromMediaId(mediaItem.id, null)
        }
    }

    private fun playMediaId(mediaId: String) {

        val nowPlaying = musicServiceConnection.nowPlaying.value

        val transportControls = musicServiceConnection.transportControls
        val isPrepared = musicServiceConnection.playbackState.value?.isPrepared ?: false
        Log.d(TAG, "mediaId: $mediaId $isPrepared")
        if (isPrepared && mediaId == nowPlaying?.id) {
            musicServiceConnection.playbackState.value?.let { playbackState ->
                when {
                    playbackState.isPlaying -> transportControls.pause()
                    playbackState.isPlayEnabled -> transportControls.play()
                    else -> {
                        // Something wrong
                    }
                }
            }
        } else {
            transportControls.playFromMediaId(mediaId, null)
        }
    }

    private fun downloadSong(mediaId: String) {
        val bundle = Bundle().apply {
            putString(MEDIA_METADATA_COMPAT_FOR_DOWNLOAD, mediaId)
        }

        musicServiceConnection.sendCommand("download_song", bundle)
    }
}

private const val TAG = "EpisodeViewModel"
const val MEDIA_METADATA_COMPAT_FOR_DOWNLOAD =
    "com.church.injilkeselamatan.audiorenungan.bundles.mediametadata"
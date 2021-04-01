/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.activities.main.chat.viewmodels

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageListenerStub
import org.linphone.core.Content
import org.linphone.core.tools.Log
import org.linphone.utils.AppUtils
import org.linphone.utils.FileUtils
import org.linphone.utils.ImageUtils

class ChatMessageContentViewModel(
    val content: Content,
    private val chatMessage: ChatMessage,
    private val listener: OnContentClickedListener?
) : ViewModel() {
    val isImage = MutableLiveData<Boolean>()
    val isVideo = MutableLiveData<Boolean>()
    val isAudio = MutableLiveData<Boolean>()
    val videoPreview = MutableLiveData<Bitmap>()

    val fileName = MutableLiveData<String>()

    val fileSize = MutableLiveData<String>()

    val downloadable = MutableLiveData<Boolean>()

    val downloadEnabled = MutableLiveData<Boolean>()

    val downloadProgress = MutableLiveData<Int>()

    val isAlone: Boolean
        get() {
            var count = 0
            for (content in chatMessage.contents) {
                if (content.isFileTransfer || content.isFile) {
                    count += 1
                }
            }
            return count == 1
        }

    private val chatMessageListener: ChatMessageListenerStub = object : ChatMessageListenerStub() {
        override fun onFileTransferProgressIndication(
            message: ChatMessage,
            c: Content,
            offset: Int,
            total: Int
        ) {
            if (c.filePath == content.filePath) {
                val percent = offset * 100 / total
                Log.d("[Content] Download progress is: $offset / $total ($percent%)")
                downloadProgress.postValue(percent)
            }
        }

        override fun onMsgStateChanged(message: ChatMessage, state: ChatMessage.State) {
            if (state == ChatMessage.State.FileTransferDone || state == ChatMessage.State.FileTransferError) {
                message.removeListener(this)
            }
            downloadEnabled.postValue(chatMessage.state != ChatMessage.State.FileTransferInProgress)
        }
    }

    init {
        fileName.value = if (content.name.isNullOrEmpty() && !content.filePath.isNullOrEmpty()) {
            FileUtils.getNameFromFilePath(content.filePath!!)
        } else {
            content.name
        }
        fileSize.value = AppUtils.bytesToDisplayableSize(content.fileSize.toLong())

        if (content.isFile || (content.isFileTransfer && chatMessage.isOutgoing)) {
            val filePath = content.filePath ?: ""
            downloadable.value = filePath.isEmpty()

            if (filePath.isNotEmpty()) {
                Log.i("[Content] Found displayable content: $filePath")
                isImage.value = FileUtils.isExtensionImage(filePath)
                isVideo.value = FileUtils.isExtensionVideo(filePath)
                isAudio.value = FileUtils.isExtensionAudio(filePath)

                if (isVideo.value == true) {
                    viewModelScope.launch {
                        withContext(Dispatchers.IO) {
                            videoPreview.postValue(ImageUtils.getVideoPreview(filePath))
                        }
                    }
                }
            } else {
                Log.w("[Content] Found content with empty path...")
                isImage.value = false
                isVideo.value = false
                isAudio.value = false
            }
        } else {
            downloadable.value = true
            isImage.value = false
            isVideo.value = false
            isAudio.value = false
        }

        downloadEnabled.value = !chatMessage.isFileTransferInProgress
        downloadProgress.value = 0
        chatMessage.addListener(chatMessageListener)
    }

    fun download() {
        val filePath = content.filePath
        if (content.isFileTransfer && (filePath == null || filePath.isEmpty())) {
            val contentName = content.name
            if (contentName != null) {
                val file = FileUtils.getFileStoragePath(contentName)
                content.filePath = file.path
                downloadEnabled.value = false

                Log.i("[Content] Started downloading $contentName into ${content.filePath}")
                chatMessage.downloadContent(content)
            }
        }
    }

    fun openFile() {
        listener?.onContentClicked(content)
    }
}

interface OnContentClickedListener {
    fun onContentClicked(content: Content)
}

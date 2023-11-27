/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
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
package org.linphone.ui.main.contacts.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.ContactsManager
import org.linphone.contacts.getListOfSipAddressesAndPhoneNumbers
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.ChatRoomParams
import org.linphone.core.Friend
import org.linphone.core.tools.Log
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.contacts.model.ContactDeviceModel
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressClickListener
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressModel
import org.linphone.ui.main.model.isInSecureMode
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils

class ContactViewModel @UiThread constructor() : ViewModel() {
    companion object {
        private const val TAG = "[Contact ViewModel]"

        private const val START_AUDIO_CALL = 0
        private const val START_VIDEO_CALL = 1
        private const val START_CONVERSATION = 2
    }

    val contact = MutableLiveData<ContactAvatarModel>()

    val sipAddressesAndPhoneNumbers = MutableLiveData<ArrayList<ContactNumberOrAddressModel>>()

    val devices = MutableLiveData<ArrayList<ContactDeviceModel>>()

    val company = MutableLiveData<String>()

    val title = MutableLiveData<String>()

    val isFavourite = MutableLiveData<Boolean>()

    val showBackButton = MutableLiveData<Boolean>()

    val expandNumbersAndAddresses = MutableLiveData<Boolean>()

    val expandDevicesTrust = MutableLiveData<Boolean>()

    val contactFoundEvent = MutableLiveData<Event<Boolean>>()

    val chatDisabled = MutableLiveData<Boolean>()

    val videoCallDisabled = MutableLiveData<Boolean>()

    val operationInProgress = MutableLiveData<Boolean>()

    val chatRoomCreationErrorEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val showLongPressMenuForNumberOrAddressEvent: MutableLiveData<Event<ContactNumberOrAddressModel>> by lazy {
        MutableLiveData<Event<ContactNumberOrAddressModel>>()
    }

    val showNumberOrAddressPickerDialogEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val openNativeContactEditor: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val openLinphoneContactEditor: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val goToConversationEvent: MutableLiveData<Event<Pair<String, String>>> by lazy {
        MutableLiveData<Event<Pair<String, String>>>()
    }

    val vCardTerminatedEvent: MutableLiveData<Event<Pair<String, File>>> by lazy {
        MutableLiveData<Event<Pair<String, File>>>()
    }

    val displayTrustProcessDialogEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val startCallToDeviceToIncreaseTrustEvent: MutableLiveData<Event<Pair<String, String>>> by lazy {
        MutableLiveData<Event<Pair<String, String>>>()
    }

    val contactRemovedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    private var expectedAction: Int = START_AUDIO_CALL
    private val listener = object : ContactNumberOrAddressClickListener {
        @UiThread
        override fun onClicked(model: ContactNumberOrAddressModel) {
            val address = model.address
            if (model.isEnabled && address != null) {
                coreContext.postOnCoreThread { core ->
                    when (expectedAction) {
                        START_AUDIO_CALL -> {
                            Log.i("$TAG Audio calling SIP address [${address.asStringUriOnly()}]")
                            val params = core.createCallParams(null)
                            params?.isVideoEnabled = false
                            coreContext.startCall(address, params)
                        }
                        START_VIDEO_CALL -> {
                            Log.i("$TAG Video calling SIP address [${address.asStringUriOnly()}]")
                            val params = core.createCallParams(null)
                            params?.isVideoEnabled = true
                            coreContext.startCall(address, params)
                        }
                        START_CONVERSATION -> {
                            Log.i(
                                "$TAG Going to conversation with SIP address [${address.asStringUriOnly()}]"
                            )
                            goToConversation(address)
                        }
                    }
                }
            } else if (!model.isEnabled) {
                Log.w(
                    "$TAG Can't call SIP address [${address?.asStringUriOnly()}], it is disabled due to currently selected mode"
                )
                // TODO: Explain why user can't call that number
            }
        }

        @UiThread
        override fun onLongPress(model: ContactNumberOrAddressModel) {
            showLongPressMenuForNumberOrAddressEvent.value = Event(model)
        }
    }

    private val contactsListener = object : ContactsManager.ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            val friend = coreContext.contactsManager.findContactById(refKey)
            if (friend != null && friend != this@ContactViewModel.friend) {
                Log.i(
                    "$TAG Found contact [${friend.name}] matching ref key [$refKey] after contacts have been loaded/updated"
                )
                this@ContactViewModel.friend = friend
                refreshContactInfo()
            }
        }
    }

    private val chatRoomListener = object : ChatRoomListenerStub() {
        @WorkerThread
        override fun onStateChanged(chatRoom: ChatRoom, newState: ChatRoom.State?) {
            val state = chatRoom.state
            val id = LinphoneUtils.getChatRoomId(chatRoom)
            Log.i("$TAG Conversation [$id] (${chatRoom.subject}) state changed: [$state]")

            if (state == ChatRoom.State.Created) {
                Log.i("$TAG Conversation [$id] successfully created")
                chatRoom.removeListener(this)
                operationInProgress.postValue(false)
                goToConversationEvent.postValue(
                    Event(
                        Pair(
                            chatRoom.localAddress.asStringUriOnly(),
                            chatRoom.peerAddress.asStringUriOnly()
                        )
                    )
                )
            } else if (state == ChatRoom.State.CreationFailed) {
                Log.e("$TAG Conversation [$id] creation has failed!")
                chatRoom.removeListener(this)
                operationInProgress.postValue(false)
                chatRoomCreationErrorEvent.postValue(Event("Error!")) // TODO FIXME: use translated string
            }
        }
    }

    private lateinit var friend: Friend

    private var refKey: String = ""

    init {
        expandNumbersAndAddresses.value = true
        expandDevicesTrust.value = false // TODO FIXME: set it to true when it will work for real

        coreContext.postOnCoreThread { core ->
            chatDisabled.postValue(corePreferences.disableChat)
            videoCallDisabled.postValue(!core.isVideoEnabled)
            coreContext.contactsManager.addListener(contactsListener)
        }
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread {
            coreContext.contactsManager.removeListener(contactsListener)
            contact.value?.destroy()
        }
    }

    @UiThread
    fun findContactByRefKey(refKey: String) {
        this.refKey = refKey

        coreContext.postOnCoreThread {
            val friend = coreContext.contactsManager.findContactById(refKey)
            if (friend != null) {
                Log.i("$TAG Found contact [${friend.name}] matching ref key [$refKey]")
                this.friend = friend

                refreshContactInfo()
                contactFoundEvent.postValue(Event(true))
            }
        }
    }

    @WorkerThread
    fun refreshContactInfo() {
        isFavourite.postValue(friend.starred)

        contact.value?.destroy()
        contact.postValue(ContactAvatarModel(friend))

        val organization = friend.organization
        if (!organization.isNullOrEmpty()) {
            company.postValue(organization!!)
        }
        val jobTitle = friend.jobTitle
        if (!jobTitle.isNullOrEmpty()) {
            title.postValue(jobTitle!!)
        }

        val addressesAndNumbers = friend.getListOfSipAddressesAndPhoneNumbers(listener)
        sipAddressesAndPhoneNumbers.postValue(addressesAndNumbers)

        val devicesList = arrayListOf<ContactDeviceModel>()
        // TODO FIXME: use real devices list from API
        devicesList.add(ContactDeviceModel("Pixel 6 Pro de Sylvain", true))
        devicesList.add(ContactDeviceModel("Sylvain Galaxy Tab S9 Pro+ Ultra", true))
        devicesList.add(
            ContactDeviceModel("MacBook Pro de Marcel", false) {
                // TODO: check if do not show dialog anymore setting is set
                if (::friend.isInitialized) {
                    startCallToDeviceToIncreaseTrustEvent.value =
                        Event(Pair(friend.name.orEmpty(), it.name))
                }
            }
        )
        devicesList.add(ContactDeviceModel("sylvain@fedora-linux-38", true))
        devices.postValue(devicesList)
    }

    @UiThread
    fun toggleNumbersAndAddressesExpand() {
        expandNumbersAndAddresses.value = expandNumbersAndAddresses.value == false
    }

    @UiThread
    fun toggleDevicesTrustExpand() {
        expandDevicesTrust.value = expandDevicesTrust.value == false
    }

    @UiThread
    fun displayTrustDialog() {
        displayTrustProcessDialogEvent.value = Event(true)
    }

    @UiThread
    fun editContact() {
        coreContext.postOnCoreThread {
            if (::friend.isInitialized) {
                val uri = friend.nativeUri
                if (uri != null) {
                    Log.i(
                        "$TAG Contact [${friend.name}] is a native contact, opening native contact editor using URI [$uri]"
                    )
                    openNativeContactEditor.postValue(Event(uri))
                } else {
                    val id = contact.value?.id.orEmpty()
                    Log.i(
                        "$TAG Contact [${friend.name}] is a Linphone contact, opening in-app contact editor using ID [$id]"
                    )
                    openLinphoneContactEditor.postValue(Event(id))
                }
            }
        }
    }

    @UiThread
    fun exportContactAsVCard() {
        coreContext.postOnCoreThread {
            if (::friend.isInitialized) {
                val vCard = friend.vcard?.asVcard4String()
                if (!vCard.isNullOrEmpty()) {
                    Log.i("$TAG Friend has been successfully dumped as vCard string")
                    val fileName = friend.name.orEmpty().replace(" ", "_").lowercase(
                        Locale.getDefault()
                    )
                    val file = FileUtils.getFileStorageCacheDir(
                        "$fileName.vcf",
                        overrideExisting = true
                    )
                    viewModelScope.launch {
                        if (FileUtils.dumpStringToFile(vCard, file)) {
                            Log.i("$TAG vCard string saved as file in cache folder")
                            vCardTerminatedEvent.postValue(Event(Pair(friend.name.orEmpty(), file)))
                        } else {
                            Log.e("$TAG Failed to save vCard string as file in cache folder")
                        }
                    }
                } else {
                    Log.e("$TAG Failed to dump contact as vCard string")
                }
            }
        }
    }

    @UiThread
    fun deleteContact() {
        coreContext.postOnCoreThread {
            if (::friend.isInitialized) {
                Log.w("$TAG Deleting friend [$friend]")
                friend.remove()
                coreContext.contactsManager.notifyContactsListChanged()
                contactRemovedEvent.postValue(Event(true))
            }
        }
    }

    @UiThread
    fun toggleFavourite() {
        coreContext.postOnCoreThread {
            val favourite = friend.starred
            Log.i(
                "$TAG Flagging contact [${friend.name}] as ${if (favourite) "no longer favourite" else "favourite"}"
            )

            friend.edit()
            friend.starred = !favourite
            friend.done()

            isFavourite.postValue(friend.starred)
            coreContext.contactsManager.notifyContactsListChanged()
        }
    }

    @UiThread
    fun startAudioCall() {
        coreContext.postOnCoreThread { core ->
            val addressesCount = friend.addresses.size
            val numbersCount = friend.phoneNumbers.size

            // Do not consider phone numbers if default account is in secure mode
            val enablePhoneNumbers = core.defaultAccount?.isInSecureMode() != true

            if (addressesCount == 1 && (numbersCount == 0 || !enablePhoneNumbers)) {
                Log.i(
                    "$TAG Only 1 SIP address found for contact [${friend.name}], starting audio call directly"
                )
                val address = friend.addresses.first()
                coreContext.startCall(address)
            } else if (addressesCount == 0 && numbersCount == 1 && enablePhoneNumbers) {
                val number = friend.phoneNumbers.first()
                val address = core.interpretUrl(number, true)
                if (address != null) {
                    Log.i(
                        "$TAG Only 1 phone number found for contact [${friend.name}], starting audio call directly"
                    )
                    coreContext.startCall(address)
                } else {
                    Log.e("$TAG Failed to interpret phone number [$number] as SIP address")
                }
            } else {
                expectedAction = START_AUDIO_CALL
                val list = sipAddressesAndPhoneNumbers.value.orEmpty()
                Log.i(
                    "$TAG [${list.size}] numbers or addresses found for contact [${friend.name}], showing selection dialog"
                )
                showNumberOrAddressPickerDialogEvent.postValue(Event(true))
            }
        }
    }

    @UiThread
    fun startVideoCall() {
        coreContext.postOnCoreThread { core ->
            val addressesCount = friend.addresses.size
            val numbersCount = friend.phoneNumbers.size

            // Do not consider phone numbers if default account is in secure mode
            val enablePhoneNumbers = core.defaultAccount?.isInSecureMode() != true

            if (addressesCount == 1 && (numbersCount == 0 || !enablePhoneNumbers)) {
                Log.i(
                    "$TAG Only 1 SIP address found for contact [${friend.name}], starting video call directly"
                )
                val address = friend.addresses.first()
                val params = core.createCallParams(null)
                params?.isVideoEnabled = true
                coreContext.startCall(address, params)
            } else if (addressesCount == 0 && numbersCount == 1 && enablePhoneNumbers) {
                val number = friend.phoneNumbers.first()
                val address = core.interpretUrl(number, true)
                if (address != null) {
                    Log.i(
                        "$TAG Only 1 phone number found for contact [${friend.name}], starting video call directly"
                    )
                    val params = core.createCallParams(null)
                    params?.isVideoEnabled = true
                    coreContext.startCall(address, params)
                } else {
                    Log.e("$TAG Failed to interpret phone number [$number] as SIP address")
                }
            } else {
                expectedAction = START_VIDEO_CALL
                val list = sipAddressesAndPhoneNumbers.value.orEmpty()
                Log.i(
                    "$TAG [${list.size}] numbers or addresses found for contact [${friend.name}], showing selection dialog"
                )
                showNumberOrAddressPickerDialogEvent.postValue(Event(true))
            }
        }
    }

    @UiThread
    fun goToConversation() {
        coreContext.postOnCoreThread { core ->
            val addressesCount = friend.addresses.size
            val numbersCount = friend.phoneNumbers.size

            // Do not consider phone numbers if default account is in secure mode
            val enablePhoneNumbers = core.defaultAccount?.isInSecureMode() != true

            if (addressesCount == 1 && (numbersCount == 0 || !enablePhoneNumbers)) {
                Log.i(
                    "$TAG Only 1 SIP address found for contact [${friend.name}], sending message directly"
                )
                goToConversation(friend.addresses.first())
            } else if (addressesCount == 0 && numbersCount == 1 && enablePhoneNumbers) {
                val number = friend.phoneNumbers.first()
                val address = core.interpretUrl(number, true)
                if (address != null) {
                    Log.i(
                        "$TAG Only 1 phone number found for contact [${friend.name}], sending message directly"
                    )
                    goToConversation(address)
                } else {
                    Log.e("$TAG Failed to interpret phone number [$number] as SIP address")
                }
            } else {
                expectedAction = START_CONVERSATION
                val list = sipAddressesAndPhoneNumbers.value.orEmpty()
                Log.i(
                    "$TAG [${list.size}] numbers or addresses found for contact [${friend.name}], showing selection dialog"
                )
                showNumberOrAddressPickerDialogEvent.postValue(Event(true))
            }
        }
    }

    @WorkerThread
    private fun goToConversation(remote: Address) {
        val core = coreContext.core
        val account = core.defaultAccount
        val localSipUri = account?.params?.identityAddress?.asStringUriOnly()
        if (!localSipUri.isNullOrEmpty()) {
            val remoteSipUri = remote.asStringUriOnly()
            Log.i(
                "$TAG Looking for existing conversation between [$localSipUri] and [$remoteSipUri]"
            )

            val params: ChatRoomParams = coreContext.core.createDefaultChatRoomParams()
            params.isGroupEnabled = false
            params.subject = AppUtils.getString(R.string.conversation_one_to_one_hidden_subject)
            params.ephemeralLifetime = 0 // Make sure ephemeral is disabled by default

            val sameDomain = remote.domain == corePreferences.defaultDomain && remote.domain == account.params.domain
            if (account.isInSecureMode() && sameDomain) {
                Log.i(
                    "$TAG Account is in secure mode & domain matches, creating a E2E conversation"
                )
                params.backend = ChatRoom.Backend.FlexisipChat
                params.isEncryptionEnabled = true
            } else if (!account.isInSecureMode()) {
                if (LinphoneUtils.isEndToEndEncryptedChatAvailable(core)) {
                    Log.i(
                        "$TAG Account is in interop mode but LIME is available, creating a E2E conversation"
                    )
                    params.backend = ChatRoom.Backend.FlexisipChat
                    params.isEncryptionEnabled = true
                } else {
                    Log.i(
                        "$TAG Account is in interop mode but LIME isn't available, creating a SIP simple conversation"
                    )
                    params.backend = ChatRoom.Backend.Basic
                    params.isEncryptionEnabled = false
                }
            } else {
                Log.e(
                    "$TAG Account is in secure mode, can't chat with SIP address of different domain [${remote.asStringUriOnly()}]"
                )
                return
            }

            val participants = arrayOf(remote)
            val localAddress = account.params.identityAddress
            val existingChatRoom = core.searchChatRoom(params, localAddress, null, participants)
            if (existingChatRoom != null) {
                Log.i(
                    "$TAG Found existing conversation [${LinphoneUtils.getChatRoomId(
                        existingChatRoom
                    )}], going to it"
                )
                goToConversationEvent.postValue(
                    Event(Pair(localSipUri, existingChatRoom.peerAddress.asStringUriOnly()))
                )
            } else {
                Log.i(
                    "$TAG No existing conversation between [$localSipUri] and [$remoteSipUri] was found, let's create it"
                )
                operationInProgress.postValue(true)
                val chatRoom = core.createChatRoom(params, localAddress, participants)
                if (chatRoom != null) {
                    if (params.backend == ChatRoom.Backend.FlexisipChat) {
                        if (chatRoom.state == ChatRoom.State.Created) {
                            val id = LinphoneUtils.getChatRoomId(chatRoom)
                            Log.i("$TAG 1-1 conversation [$id] has been created")
                            operationInProgress.postValue(false)
                            goToConversationEvent.postValue(
                                Event(
                                    Pair(
                                        chatRoom.localAddress.asStringUriOnly(),
                                        chatRoom.peerAddress.asStringUriOnly()
                                    )
                                )
                            )
                        } else {
                            Log.i("$TAG Conversation isn't in Created state yet, wait for it")
                            chatRoom.addListener(chatRoomListener)
                        }
                    } else {
                        val id = LinphoneUtils.getChatRoomId(chatRoom)
                        Log.i("$TAG Conversation successfully created [$id]")
                        operationInProgress.postValue(false)
                        goToConversationEvent.postValue(
                            Event(
                                Pair(
                                    chatRoom.localAddress.asStringUriOnly(),
                                    chatRoom.peerAddress.asStringUriOnly()
                                )
                            )
                        )
                    }
                } else {
                    Log.e(
                        "$TAG Failed to create 1-1 conversation with [${remote.asStringUriOnly()}]!"
                    )
                    operationInProgress.postValue(false)
                    chatRoomCreationErrorEvent.postValue(Event("Error!")) // TODO FIXME: use translated string
                }
            }
        }
    }
}

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
package org.linphone.ui.main.history.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.core.view.doOnPreDraw
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.contacts.getListOfSipAddressesAndPhoneNumbers
import org.linphone.core.tools.Log
import org.linphone.databinding.StartCallFragmentBinding
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressClickListener
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressModel
import org.linphone.ui.main.contacts.model.NumberOrAddressPickerDialogModel
import org.linphone.ui.main.fragment.GenericFragment
import org.linphone.ui.main.history.adapter.ContactsAndSuggestionsListAdapter
import org.linphone.ui.main.history.model.ContactOrSuggestionModel
import org.linphone.ui.main.history.viewmodel.StartCallViewModel
import org.linphone.ui.main.model.isInSecureMode
import org.linphone.utils.DialogUtils
import org.linphone.utils.RecyclerViewHeaderDecoration
import org.linphone.utils.addCharacterAtPosition
import org.linphone.utils.hideKeyboard
import org.linphone.utils.removeCharacterAtPosition
import org.linphone.utils.setKeyboardInsetListener
import org.linphone.utils.showKeyboard

@UiThread
class StartCallFragment : GenericFragment() {
    companion object {
        private const val TAG = "[Start Call Fragment]"
    }

    private lateinit var binding: StartCallFragmentBinding

    private val viewModel: StartCallViewModel by navGraphViewModels(
        R.id.main_nav_graph
    )

    private lateinit var adapter: ContactsAndSuggestionsListAdapter

    private val listener = object : ContactNumberOrAddressClickListener {
        @UiThread
        override fun onClicked(model: ContactNumberOrAddressModel) {
            val address = model.address
            if (address != null) {
                coreContext.postOnCoreThread {
                    coreContext.startCall(address)
                }
            }
        }

        @UiThread
        override fun onLongPress(model: ContactNumberOrAddressModel) {
        }
    }

    private var numberOrAddressPickerDialog: Dialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = StartCallFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()

        binding.lifecycleOwner = viewLifecycleOwner

        viewModel.title.value = getString(R.string.history_call_start_title)
        binding.viewModel = viewModel

        binding.setBackClickListener {
            goBack()
        }

        binding.setHideNumpadClickListener {
            viewModel.hideNumpad()
        }

        adapter = ContactsAndSuggestionsListAdapter(viewLifecycleOwner)
        binding.contactsAndSuggestionsList.setHasFixedSize(true)
        binding.contactsAndSuggestionsList.adapter = adapter

        val headerItemDecoration = RecyclerViewHeaderDecoration(requireContext(), adapter, true)
        binding.contactsAndSuggestionsList.addItemDecoration(headerItemDecoration)

        adapter.contactClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                startCall(model)
            }
        }

        binding.contactsAndSuggestionsList.layoutManager = LinearLayoutManager(requireContext())

        viewModel.contactsAndSuggestionsList.observe(
            viewLifecycleOwner
        ) {
            Log.i("$TAG Contacts & suggestions list is ready with [${it.size}] items")
            val count = adapter.itemCount
            adapter.submitList(it)

            if (count == 0 && it.isNotEmpty()) {
                (view.parent as? ViewGroup)?.doOnPreDraw {
                    startPostponedEnterTransition()
                }
            }
        }

        viewModel.searchFilter.observe(viewLifecycleOwner) { filter ->
            val trimmed = filter.trim()
            viewModel.applyFilter(trimmed)
        }

        viewModel.removedCharacterAtCurrentPositionEvent.observe(viewLifecycleOwner) {
            it.consume {
                binding.searchBar.removeCharacterAtPosition()
            }
        }

        viewModel.appendDigitToSearchBarEvent.observe(viewLifecycleOwner) {
            it.consume { digit ->
                binding.searchBar.addCharacterAtPosition(digit)
            }
        }

        viewModel.requestKeyboardVisibilityChangedEvent.observe(viewLifecycleOwner) {
            it.consume { show ->
                if (show) {
                    // To automatically open keyboard
                    binding.searchBar.showKeyboard(requireActivity().window)
                } else {
                    binding.searchBar.requestFocus()
                    binding.searchBar.hideKeyboard()
                }
            }
        }

        viewModel.isNumpadVisible.observe(viewLifecycleOwner) { visible ->
            val standardBottomSheetBehavior = BottomSheetBehavior.from(binding.numpadLayout.root)
            if (visible) {
                standardBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                standardBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        binding.root.setKeyboardInsetListener { keyboardVisible ->
            if (keyboardVisible) {
                viewModel.isNumpadVisible.value = false
            }
        }
    }

    override fun onPause() {
        super.onPause()

        viewModel.isNumpadVisible.value = false

        numberOrAddressPickerDialog?.dismiss()
        numberOrAddressPickerDialog = null
    }

    private fun startCall(model: ContactOrSuggestionModel) {
        coreContext.postOnCoreThread { core ->
            val friend = model.friend
            if (friend == null) {
                coreContext.startCall(model.address)
                return@postOnCoreThread
            }

            val addressesCount = friend.addresses.size
            val numbersCount = friend.phoneNumbers.size

            // Do not consider phone numbers if default account is in secure mode
            val enablePhoneNumbers = core.defaultAccount?.isInSecureMode() != true

            if (addressesCount == 1 && (numbersCount == 0 || !enablePhoneNumbers)) {
                Log.i(
                    "$TAG Only 1 SIP address found for contact [${friend.name}], starting call directly"
                )
                val address = friend.addresses.first()
                coreContext.startCall(address)
            } else if (addressesCount == 0 && numbersCount == 1 && enablePhoneNumbers) {
                val number = friend.phoneNumbers.first()
                val address = core.interpretUrl(number, true)
                if (address != null) {
                    Log.i(
                        "$TAG Only 1 phone number found for contact [${friend.name}], starting call directly"
                    )
                    coreContext.startCall(address)
                } else {
                    Log.e("$TAG Failed to interpret phone number [$number] as SIP address")
                }
            } else {
                val list = friend.getListOfSipAddressesAndPhoneNumbers(listener)
                Log.i(
                    "$TAG [${list.size}] numbers or addresses found for contact [${friend.name}], showing selection dialog"
                )

                coreContext.postOnMainThread {
                    val numberOrAddressModel = NumberOrAddressPickerDialogModel(list)
                    val dialog =
                        DialogUtils.getNumberOrAddressPickerDialog(
                            requireActivity(),
                            numberOrAddressModel
                        )
                    numberOrAddressPickerDialog = dialog

                    numberOrAddressModel.dismissEvent.observe(viewLifecycleOwner) { event ->
                        event.consume {
                            dialog.dismiss()
                        }
                    }

                    dialog.show()
                }
            }
        }
    }
}

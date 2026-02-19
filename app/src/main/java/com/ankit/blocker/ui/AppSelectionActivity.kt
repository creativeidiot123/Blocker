package com.ankit.blocker.ui

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ankit.blocker.R
import com.ankit.blocker.utils.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(
    val packageName: String,
    val label: String,
    var isSelected: Boolean
)

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var adapter: AppAdapter

    // Full list of apps to filter against
    private var allApps: List<AppItem> = emptyList()

    // Track pending changes in memory before saving
    private val selectedPackages = mutableSetOf<String>()

    // Debounce job for search input
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        title = "Select Apps to Block (Anki)"

        recyclerView = findViewById(R.id.appRecyclerView)
        searchEditText = findViewById(R.id.searchEditText)
        saveButton = findViewById(R.id.saveButton)

        setupRecyclerView()
        setupSearch()

        saveButton.setOnClickListener {
            saveSelection()
        }

        loadApps()
    }

    private fun setupRecyclerView() {
        adapter = AppAdapter { appItem, isChecked, holderCheckBox ->
            handleItemClick(appItem, isChecked, holderCheckBox)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true) // Optimization
    }

    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Debounce: cancel previous job and wait 300ms before filtering
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300L)
                    filterApps(s.toString())
                }
            }
        })
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.Default) {
            val pm = packageManager
            // Read saved Anki blocked apps
            val savedSelection = PreferencesHelper.getAnkiBlockedApps(this@AppSelectionActivity)
            selectedPackages.addAll(savedSelection) // Initialize local set

            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)

            val resolvedApps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }

            val items = resolvedApps.map { appInfo ->
                AppItem(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(pm).toString(),
                    isSelected = savedSelection.contains(appInfo.packageName)
                )
            }.sortedBy { it.label }

            allApps = items

            withContext(Dispatchers.Main) {
                adapter.submitList(items)
            }
        }
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
    }

    private fun handleItemClick(appItem: AppItem, isChecked: Boolean, checkBox: CheckBox) {
        if (!isChecked) {
            // Unchecking - requires password if enabled
            if (PreferencesHelper.isPasswordSet(this)) {
                // Revert visual state immediately until confirmed
                checkBox.isChecked = true
                
                val passwordDialog = com.ankit.blocker.utils.PasswordDialog(this)
                passwordDialog.showPasswordDialog(object : com.ankit.blocker.utils.PasswordDialog.PasswordCallback {
                    override fun onPasswordCorrect() {
                        // Confirmed
                        checkBox.isChecked = false
                        appItem.isSelected = false // Update model
                        selectedPackages.remove(appItem.packageName)
                    }

                    override fun onPasswordCancelled() {
                        // Remain checked
                    }
                })
            } else {
                // No password, just update
                appItem.isSelected = false
                selectedPackages.remove(appItem.packageName)
            }
        } else {
            // Checking - allowed freely
            appItem.isSelected = true
            selectedPackages.add(appItem.packageName)
        }
    }

    private fun saveSelection() {
        PreferencesHelper.setAnkiBlockedApps(this, selectedPackages)
        Toast.makeText(this, "Selection Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}

class AppAdapter(
    private val onItemChanged: (AppItem, Boolean, CheckBox) -> Unit
) : ListAdapter<AppItem, AppAdapter.AppViewHolder>(AppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_selection, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkBox: CheckBox = itemView.findViewById(R.id.appCheckBox)

        fun bind(item: AppItem) {
            checkBox.text = item.label
            // Remove listener before setting state to avoid triggering logic
            checkBox.setOnCheckedChangeListener(null) 
            checkBox.isChecked = item.isSelected
            
            // We use click listener to capture User Intent vs programmatic changes
            checkBox.setOnClickListener {
                onItemChanged(item, checkBox.isChecked, checkBox)
            }
        }
    }
}

class AppDiffCallback : DiffUtil.ItemCallback<AppItem>() {
    override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
        return oldItem.packageName == newItem.packageName
    }

    override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
        return oldItem == newItem
    }
}

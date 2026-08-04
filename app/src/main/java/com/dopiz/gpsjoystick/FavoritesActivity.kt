package com.dopiz.gpsjoystick

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Favorites as a full screen, mirroring [GpxLibraryActivity]: a Material Toolbar (back + title +
 * overflow for 選取 / 管理分類 / 全部清除), a category filter chip row, a list of saved favorites
 * (name + 分類 + "lat, lng"), and a per-row overflow menu for 重新命名 / 編輯座標 / 移動到分類 / 刪除.
 * Tapping a row CHOOSES that favorite: it returns the coordinate to [MapActivity] via the
 * Activity Result API (EXTRA_FAV_LAT / EXTRA_FAV_LNG). Long-pressing a row enters multi-select,
 * where the bottom bar batch-moves the checked rows into one category.
 */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var favList: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var categoryChips: ChipGroup
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView

    /** null = 「全部」 */
    private var filterCategory: String? = null
    private var selectionMode = false
    private val selected = mutableSetOf<Int>()

    private val adapter = FavAdapter()

    /** Origin for the per-row cooldown estimate; refreshed on every [renderList]. */
    private var origin = MockLocationService.state.value

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { doImport(it) }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { doExport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        favList = findViewById(R.id.favList)
        favList.layoutManager = LinearLayoutManager(this)
        favList.adapter = adapter
        emptyHint = findViewById(R.id.emptyHint)
        categoryChips = findViewById(R.id.categoryChips)
        selectionBar = findViewById(R.id.selectionBar)
        selectionCount = findViewById(R.id.selectionCount)

        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            if (FavoritesStore.list(this).isEmpty()) {
                Toast.makeText(this, R.string.fav_export_empty, Toast.LENGTH_SHORT).show()
            } else {
                val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                exportLauncher.launch("favorites-$stamp.json")
            }
        }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }
        findViewById<MaterialButton>(R.id.btnBatchCategory).setOnClickListener { batchCategory() }
        findViewById<MaterialButton>(R.id.btnSelectExit).setOnClickListener { exitSelection() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.favorites, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_select_mode -> {
            if (selectionMode) exitSelection() else enterSelection()
            true
        }
        R.id.action_manage_categories -> { manageCategoriesDialog(); true }
        R.id.action_clear_all -> { clearAllDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---- 一鍵清除 ---------------------------------------------------------

    private fun clearAllDialog() {
        val count = FavoritesStore.list(this).size
        if (count == 0) {
            Toast.makeText(this, R.string.fav_clear_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_clear_title)
            .setMessage(getString(R.string.fav_clear_message, count))
            .setPositiveButton(R.string.fav_clear_confirm) { _, _ ->
                FavoritesStore.clear(this)
                exitSelection()
                Toast.makeText(this, R.string.fav_cleared, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            ?.setTextColor(getColor(R.color.brand_error))
    }

    // ---- 分類 -------------------------------------------------------------

    private fun renderChips() {
        categoryChips.removeAllViews()
        val options = listOf<String?>(null) + FavoritesStore.listCategories(this)
        if (filterCategory != null && filterCategory !in options) filterCategory = null
        options.forEachIndexed { i, name ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = name ?: getString(R.string.fav_category_all)
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = name == filterCategory
                setOnClickListener {
                    filterCategory = name
                    renderList()
                }
            }
            categoryChips.addView(chip, i)
        }
    }

    private fun manageCategoriesDialog() {
        val custom = FavoritesStore.listCategories(this).drop(1)
        val labels = (custom + getString(R.string.fav_category_add)).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_manage_categories)
            .setItems(labels) { _, which ->
                if (which == custom.size) addCategoryDialog { render(); manageCategoriesDialog() }
                else categoryActionsDialog(custom[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun categoryActionsDialog(name: String) {
        val actions = arrayOf(getString(R.string.gpx_rename), getString(R.string.gpx_delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(actions) { _, which ->
                if (which == 0) renameCategoryDialog(name) else deleteCategoryDialog(name)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun addCategoryDialog(onAdded: (String) -> Unit) {
        val input = textInput("", getString(R.string.fav_category_name_hint))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_category_add)
            .setView(wrap(input))
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val name = input.text.toString().trim()
                val existing = FavoritesStore.listCategories(this).drop(1)
                when {
                    FavoritesStore.addCategory(this, name) -> {
                        Toast.makeText(
                            this, getString(R.string.fav_category_added, name), Toast.LENGTH_SHORT
                        ).show()
                        onAdded(name)
                    }
                    existing.contains(name) -> onAdded(name)
                    else -> Toast.makeText(this, R.string.fav_category_duplicate, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun renameCategoryDialog(old: String) {
        val input = textInput(old, getString(R.string.fav_category_name_hint))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_category_rename_title)
            .setView(wrap(input))
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val name = input.text.toString().trim()
                if (FavoritesStore.renameCategory(this, old, name)) {
                    if (filterCategory == old) filterCategory = name
                    render()
                } else {
                    Toast.makeText(this, R.string.fav_category_duplicate, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deleteCategoryDialog(name: String) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_category_delete_title)
            .setMessage(getString(R.string.fav_category_delete_message, name))
            .setPositiveButton(R.string.gpx_delete) { _, _ ->
                FavoritesStore.deleteCategory(this, name)
                if (filterCategory == name) filterCategory = null
                Toast.makeText(
                    this, getString(R.string.fav_category_deleted, name), Toast.LENGTH_SHORT
                ).show()
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            ?.setTextColor(getColor(R.color.brand_error))
    }

    /** Pick one category (with an inline 「新增分類…」 escape hatch) then hand it to [onPicked]. */
    private fun pickCategoryDialog(titleRes: Int, onPicked: (String) -> Unit) {
        val cats = FavoritesStore.listCategories(this)
        val labels = (cats + getString(R.string.fav_category_add_inline)).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setItems(labels) { _, which ->
                if (which == cats.size) addCategoryDialog { onPicked(it) } else onPicked(cats[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---- 多選 -------------------------------------------------------------

    private fun enterSelection() {
        selectionMode = true
        selected.clear()
        render()
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        render()
    }

    /**
     * [index] is the position in the FULL store list; [position] is the adapter position of the
     * row that was tapped, so only that row has to be rebound.
     */
    private fun toggleSelection(index: Int, position: Int) {
        if (!selected.add(index)) selected.remove(index)
        selectionCount.text = getString(R.string.fav_selected_count, selected.size)
        if (position == RecyclerView.NO_POSITION) renderList() else adapter.notifyItemChanged(position)
    }

    private fun batchCategory() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.fav_select_none, Toast.LENGTH_SHORT).show()
            return
        }
        val targets = selected.toList()
        pickCategoryDialog(R.string.fav_batch_add_category) { category ->
            FavoritesStore.setCategory(this, targets, category)
            Toast.makeText(
                this,
                getString(R.string.fav_batch_moved, targets.size, category),
                Toast.LENGTH_SHORT,
            ).show()
            exitSelection()
        }
    }

    // ---- 列表 -------------------------------------------------------------

    private fun render() {
        renderChips()
        renderList()
    }

    private fun renderList() {
        val visible = FavoritesStore.list(this).withIndex()
            .filter { (_, f) -> filterCategory == null || f.category == filterCategory }
            .map { (index, fav) -> Row(index, fav) }
        origin = MockLocationService.state.value
        emptyHint.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        selectionCount.text = getString(R.string.fav_selected_count, selected.size)
        adapter.submit(visible)
    }

    /** A visible row: [index] is its position in the FULL store list (what every store call takes). */
    private data class Row(val index: Int, val fav: FavoritesStore.Fav)

    private inner class FavAdapter : RecyclerView.Adapter<FavRowHolder>() {
        private var items: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(rows: List<Row>) {
            items = rows
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavRowHolder =
            FavRowHolder(layoutInflater.inflate(R.layout.item_favorite, parent, false))

        override fun onBindViewHolder(holder: FavRowHolder, position: Int) = holder.bind(items[position])
    }

    private inner class FavRowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.favName)
        private val meta: TextView = view.findViewById(R.id.favMeta)
        private val category: TextView = view.findViewById(R.id.favCategory)
        private val cooldown: TextView = view.findViewById(R.id.favCooldown)
        private val check: MaterialCheckBox = view.findViewById(R.id.favCheck)
        private val menu: MaterialButton = view.findViewById(R.id.btnMenu)
        private val walk: MaterialButton = view.findViewById(R.id.btnFavWalk)
        private val teleport: MaterialButton = view.findViewById(R.id.btnFavTeleport)
        private val chooser: View = view.findViewById(R.id.rowChoose)
        private val actions: ViewGroup = walk.parent as ViewGroup
        private var row: Row? = null

        init {
            menu.setOnClickListener { v -> row?.let { showItemMenu(v, it.index, it.fav) } }
            walk.setOnClickListener { row?.let { choose(it.fav, ACTION_WALK) } }
            teleport.setOnClickListener { row?.let { choose(it.fav, ACTION_TELEPORT) } }
            chooser.setOnClickListener {
                val r = row ?: return@setOnClickListener
                if (selectionMode) toggleSelection(r.index, adapterPosition)
                else choose(r.fav, ACTION_TELEPORT)
            }
            chooser.setOnLongClickListener {
                val r = row ?: return@setOnLongClickListener true
                val position = adapterPosition
                if (!selectionMode) enterSelection()
                toggleSelection(r.index, position)
                true
            }
        }

        fun bind(item: Row) {
            row = item
            val fav = item.fav
            val estimate = PokemonGoCooldown.estimate(
                origin.latitude, origin.longitude, fav.lat, fav.lng
            )
            name.text = fav.label
            meta.text = "${fmt(fav.lat)}, ${fmt(fav.lng)}"
            category.text = getString(R.string.fav_category_meta, fav.category)
            cooldown.text = getString(
                R.string.fav_cooldown,
                formatDistance(estimate.distanceMeters),
                formatCooldown(estimate.waitSeconds),
            )
            check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            check.isChecked = item.index in selected
            actions.visibility = if (selectionMode) View.GONE else View.VISIBLE
            menu.visibility = if (selectionMode) View.GONE else View.VISIBLE
        }
    }

    private fun choose(fav: FavoritesStore.Fav, action: String) {
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_FAV_LAT, fav.lat)
                .putExtra(EXTRA_FAV_LNG, fav.lng)
                .putExtra(EXTRA_FAV_ACTION, action),
        )
        finish()
    }

    private fun showItemMenu(anchor: View, index: Int, fav: FavoritesStore.Fav) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.favorite_item, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> { renameDialog(index, fav); true }
                    R.id.action_edit_coord -> { editCoordDialog(index, fav); true }
                    R.id.action_move_category -> {
                        pickCategoryDialog(R.string.fav_move_category) { category ->
                            FavoritesStore.setCategory(this@FavoritesActivity, listOf(index), category)
                            Toast.makeText(
                                this@FavoritesActivity,
                                getString(R.string.fav_batch_moved, 1, category),
                                Toast.LENGTH_SHORT,
                            ).show()
                            render()
                        }
                        true
                    }
                    R.id.action_delete -> { deleteDialog(index, fav); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun renameDialog(index: Int, fav: FavoritesStore.Fav) {
        val input = textInput(fav.label, getString(R.string.fav_name_hint))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_rename_title)
            .setView(wrap(input))
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    FavoritesStore.rename(this, index, name)
                    render()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Two decimal fields; invalid values show an inline error and keep the dialog open. */
    private fun editCoordDialog(index: Int, fav: FavoritesStore.Fav) {
        val decimal = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val latInput = textInput(fmt(fav.lat), getString(R.string.fav_lat_hint), decimal)
        val lngInput = textInput(fmt(fav.lng), getString(R.string.fav_lng_hint), decimal)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(latInput)
            addView(lngInput)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_edit_coord_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_save, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val lat = latInput.text.toString().trim().toDoubleOrNull()
            val lng = lngInput.text.toString().trim().toDoubleOrNull()
            var ok = true
            if (lat == null || lat < -90.0 || lat > 90.0) {
                latInput.error = getString(R.string.fav_lat_invalid); ok = false
            }
            if (lng == null || lng < -180.0 || lng > 180.0) {
                lngInput.error = getString(R.string.fav_lng_invalid); ok = false
            }
            if (!ok) return@setOnClickListener
            FavoritesStore.updateAt(this, index, fav.copy(lat = lat!!, lng = lng!!))
            Toast.makeText(this, R.string.fav_coord_updated, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            render()
        }
    }

    private fun deleteDialog(index: Int, fav: FavoritesStore.Fav) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.fav_delete_confirm, fav.label))
            .setPositiveButton(R.string.gpx_delete) { _, _ ->
                FavoritesStore.removeAt(this, index)
                selected.clear()
                Toast.makeText(this, getString(R.string.fav_deleted, fav.label), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun textInput(value: String, hintText: String, type: Int? = null): EditText =
        EditText(this).apply {
            setText(value)
            hint = hintText
            setSingleLine()
            type?.let { inputType = it }
            setSelection(text.length)
        }

    private fun wrap(view: View): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(view)
        }
    }

    private fun doExport(dest: Uri) {
        val json = FavoritesStore.exportJson(this)
        val count = FavoritesStore.list(this).size
        val ok = runCatching {
            contentResolver.openOutputStream(dest)?.use { it.write(json.toByteArray()) }
                ?: throw java.io.IOException("no stream")
        }.isSuccess
        Toast.makeText(
            this,
            if (ok) getString(R.string.fav_export_ok, count) else getString(R.string.fav_export_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun doImport(uri: Uri) {
        val result = runCatching {
            val json = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: throw java.io.IOException("no stream")
            FavoritesStore.importMerged(this, json)
        }
        result.onSuccess { (imported, skipped) ->
            Toast.makeText(
                this, getString(R.string.fav_imported, imported, skipped), Toast.LENGTH_SHORT
            ).show()
            render()
        }.onFailure { e ->
            Toast.makeText(
                this, getString(R.string.fav_import_failed, e.message ?: "?"), Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun fmt(v: Double): String = "%.5f".format(v)

    private fun formatDistance(meters: Double): String = when {
        meters < 1_000.0 -> getString(R.string.distance_meters, meters.toInt())
        meters < 100_000.0 -> getString(R.string.distance_km_decimal, meters / 1_000.0)
        else -> getString(R.string.distance_km_whole, (meters / 1_000.0).toInt())
    }

    private fun formatCooldown(seconds: Int): String = when {
        seconds == 0 -> getString(R.string.cooldown_none)
        seconds < 60 -> getString(R.string.cooldown_seconds, seconds)
        seconds % 3_600 == 0 -> getString(R.string.cooldown_hours, seconds / 3_600)
        seconds >= 3_600 -> getString(
            R.string.cooldown_hours_minutes, seconds / 3_600, (seconds % 3_600) / 60
        )
        else -> getString(R.string.cooldown_minutes, seconds / 60)
    }

    companion object {
        const val EXTRA_FAV_LAT = "fav_lat"
        const val EXTRA_FAV_LNG = "fav_lng"
        const val EXTRA_FAV_ACTION = "fav_action"
        const val ACTION_TELEPORT = "teleport"
        const val ACTION_WALK = "walk"
    }
}

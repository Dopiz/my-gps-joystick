package com.dopiz.gpsjoystick

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Favorites as a full screen, mirroring [GpxLibraryActivity]: a Material Toolbar (back + title),
 * a list of saved favorites (name + "lat, lng"), and a per-row overflow menu for 重新命名 / 刪除.
 * Tapping a row CHOOSES that favorite: it returns the coordinate to [MapActivity] via the
 * Activity Result API (EXTRA_FAV_LAT / EXTRA_FAV_LNG).
 */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        listContainer = findViewById(R.id.listContainer)
        emptyHint = findViewById(R.id.emptyHint)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val favs = FavoritesStore.list(this)
        emptyHint.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        favs.forEachIndexed { index, fav ->
            val row = inflater.inflate(R.layout.item_favorite, listContainer, false)
            row.findViewById<TextView>(R.id.favName).text = fav.label
            row.findViewById<TextView>(R.id.favMeta).text = "${fmt(fav.lat)}, ${fmt(fav.lng)}"
            row.findViewById<LinearLayout>(R.id.rowChoose).setOnClickListener { choose(fav) }
            row.findViewById<MaterialButton>(R.id.btnMenu).setOnClickListener { v ->
                showItemMenu(v, index, fav)
            }
            listContainer.addView(row)
        }
    }

    private fun choose(fav: FavoritesStore.Fav) {
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_FAV_LAT, fav.lat)
                .putExtra(EXTRA_FAV_LNG, fav.lng),
        )
        finish()
    }

    private fun showItemMenu(anchor: View, index: Int, fav: FavoritesStore.Fav) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.favorite_item, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> { renameDialog(index, fav); true }
                    R.id.action_delete -> { deleteDialog(index, fav); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun renameDialog(index: Int, fav: FavoritesStore.Fav) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(fav.label)
            setSelection(text.length)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_rename_title)
            .setView(container)
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

    private fun deleteDialog(index: Int, fav: FavoritesStore.Fav) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.fav_delete_confirm, fav.label))
            .setPositiveButton(R.string.gpx_delete) { _, _ ->
                FavoritesStore.removeAt(this, index)
                Toast.makeText(this, getString(R.string.fav_deleted, fav.label), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun fmt(v: Double): String = "%.5f".format(v)

    companion object {
        const val EXTRA_FAV_LAT = "fav_lat"
        const val EXTRA_FAV_LNG = "fav_lng"
    }
}

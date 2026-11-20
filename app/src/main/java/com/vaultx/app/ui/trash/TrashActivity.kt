package com.vaultx.app.ui.trash

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.model.FileItem
import com.vaultx.app.databinding.ActivityTrashBinding
import com.vaultx.app.databinding.ItemTrashFileBinding

class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var trashAdapter: TrashAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarTrash.setNavigationOnClickListener { finish() }

        trashAdapter = TrashAdapter(
            items = emptyList(),
            onRestore = { file ->
                VaultXApplication.instance.databaseHelper.restoreFromTrash(file.id)
                loadTrash()
                Toast.makeText(this, "${file.name} restored to files!", Toast.LENGTH_SHORT).show()
            },
            onDeleteForever = { file ->
                AlertDialog.Builder(this, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
                    .setTitle(R.string.action_delete_forever)
                    .setMessage("Permanently delete ${file.name}?\n\nThis will remove it from your device storage AND from your private Telegram Cloud channel.")
                    .setPositiveButton("Delete Forever") { _, _ ->
                        VaultXApplication.instance.telegramClient.deleteFileFromCloudAndLocal(file) {
                            loadTrash()
                            Toast.makeText(this, "Deleted from Vault & Telegram channel", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.rvTrash.layoutManager = LinearLayoutManager(this)
        binding.rvTrash.adapter = trashAdapter

        loadTrash()
    }

    private fun loadTrash() {
        val trashFiles = VaultXApplication.instance.databaseHelper.getTrashFiles()
        trashAdapter.updateData(trashFiles)

        if (trashFiles.isEmpty()) {
            binding.layoutEmptyTrash.visibility = View.VISIBLE
            binding.rvTrash.visibility = View.GONE
        } else {
            binding.layoutEmptyTrash.visibility = View.GONE
            binding.rvTrash.visibility = View.VISIBLE
        }
    }

    inner class TrashAdapter(
        private var items: List<FileItem>,
        private val onRestore: (FileItem) -> Unit,
        private val onDeleteForever: (FileItem) -> Unit
    ) : RecyclerView.Adapter<TrashAdapter.TrashViewHolder>() {

        fun updateData(newItems: List<FileItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrashViewHolder {
            val itemBinding = ItemTrashFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return TrashViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: TrashViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class TrashViewHolder(private val itemBinding: ItemTrashFileBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(file: FileItem) {
                itemBinding.tvTrashFileName.text = file.name
                itemBinding.tvTrashFileSize.text = "${file.formattedSize} • ${file.category.name}"

                itemBinding.btnTrashRestore.setOnClickListener { onRestore(file) }
                itemBinding.btnTrashDeleteForever.setOnClickListener { onDeleteForever(file) }
            }
        }
    }
}

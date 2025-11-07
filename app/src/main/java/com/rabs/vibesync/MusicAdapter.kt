package com.rabs.vibesync

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rabs.vibesync.databinding.ItemMusicBinding

class MusicAdapter(
    private val musicList: ArrayList<MusicModel>,
    private val listener: OnMusicClickListener
) : RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    interface OnMusicClickListener {
        fun onMusicClick(position: Int)
    }

    inner class MusicViewHolder(val binding: ItemMusicBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val binding = ItemMusicBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MusicViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        val item = musicList[position]
        holder.binding.tvMusicTitle.text = item.title
        holder.itemView.setOnClickListener {
            listener.onMusicClick(position)
        }
    }

    override fun getItemCount(): Int = musicList.size
}

package com.prompter.app.data

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prompter.app.databinding.ItemScriptBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScriptAdapter(
    private val onTap: (Script) -> Unit,
    private val onDelete: (Script) -> Unit,
) : RecyclerView.Adapter<ScriptAdapter.VH>() {

    private val items = ArrayList<Script>()
    private val fmt = SimpleDateFormat("yyyy.M.d", Locale.KOREA)

    fun submit(list: List<Script>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b: ItemScriptBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemScriptBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = items[pos]
        h.b.title.text = s.title
        h.b.date.text = fmt.format(Date(s.updatedAt))
        h.b.root.setOnClickListener { onTap(s) }
        h.b.btnDelete.setOnClickListener { onDelete(s) }
    }
}

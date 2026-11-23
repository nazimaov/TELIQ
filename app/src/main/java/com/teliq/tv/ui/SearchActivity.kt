package com.teliq.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teliq.tv.PlayerActivity
import com.teliq.tv.R
import com.teliq.tv.TeliqApp

/** Поиск каналов по названию — экран в стиле Spotlight. */
class SearchActivity : FragmentActivity() {

    private lateinit var input: EditText
    private lateinit var results: RecyclerView
    private lateinit var adapter: ChannelGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.search_input)
        results = findViewById(R.id.search_results)
        val lm = GridLayoutManager(this, 3)
        results.layoutManager = lm
        results.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) {
                val avail = v.width - v.paddingLeft - v.paddingRight
                if (avail > 0) lm.spanCount = Ui.gridSpan(this, avail)
            }
        }

        adapter = ChannelGridAdapter(
            items = emptyList(),
            onClick = { ch ->
                val intent = Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_CHANNEL_NUMBER, ch.number)
                startActivity(intent)
            },
        )
        results.adapter = adapter

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runSearch(s?.toString().orEmpty())
            }
        })
        input.requestFocus()
    }

    private fun runSearch(q: String) {
        val t = q.trim().lowercase()
        if (t.isEmpty()) {
            adapter.setItems(emptyList())
            return
        }
        val items = TeliqApp.instance.channels
            .filter { t in it.name.lowercase() }
            .take(60)
        adapter.setItems(items)
    }
}

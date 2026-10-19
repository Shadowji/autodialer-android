package com.paras.dialer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

class MainActivity : Activity() {
    private val brand = Color.parseColor("#0B5D7A")
    private val soft = Color.parseColor("#E4EEF2")
    private val bg = Color.parseColor("#F5F7F9")
    private val ink = Color.parseColor("#14212B")
    private val mute = Color.parseColor("#5D6B76")
    private val line = Color.parseColor("#DDE3E8")
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private var base = ""; private var token = ""; private var agentName = ""
    private var leads = JSONArray(); private var idx = 0
    private var dispositions = listOf("Interested", "Not Interested", "Call Back", "Wrong Number", "Not Reachable", "Already Done")
    private var dialing = false; private var callStart = 0L
    private val prefs by lazy { getSharedPreferences("dialer", 0) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = brand
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(bg); addView(root) })
        base = prefs.getString("base", "") ?: ""; token = prefs.getString("token", "") ?: ""; agentName = prefs.getString("name", "") ?: ""
        if (token.isEmpty()) loginUi() else loadLeads()
    }

    // Call ended -> dialer closes -> our app returns to the front -> ask for the remark
    override fun onResume() { super.onResume(); if (dialing) { dialing = false; remarkDialog() } }

    // ---------- small UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(c: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun tv(t: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun btn(t: String, bgc: Int = brand, fg: Int = Color.WHITE, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 17f; setTextColor(fg); background = shape(bgc, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14)); setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }
    private fun field(h: String, pw: Boolean = false) = EditText(this).apply {
        hint = h; background = shape(Color.WHITE, 12, line); setPadding(dp(14), dp(12), dp(14), dp(12))
        if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE, 18, line); setPadding(dp(22), dp(22), dp(22), dp(22))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
    }
    private fun screen(title: String, sub: String = "") {
        root.removeAllViews()
        val h = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(brand); setPadding(dp(24), dp(28), dp(24), dp(28)) }
        h.addView(tv(title, 26f, Color.WHITE, true))
        if (sub.isNotEmpty()) h.addView(tv(sub, 14f, Color.parseColor("#CFE6EF")))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(24)) }
        root.addView(h); root.addView(body)
    }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun http(path: String, bodyJson: JSONObject?, cb: (Int, String) -> Unit) = Thread {
        try {
            val c = URL("$base/api$path").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("Content-Type", "application/json")
            if (bodyJson != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.write(bodyJson.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
            runOnUiThread { cb(code, txt) }
        } catch (e: Exception) { runOnUiThread { toast("Can't reach the server. Check the server address and your internet.") } }
    }.start()

    // ---------- screens ----------
    private fun loginUi() {
        screen("Welcome", "Log in to start calling")
        val c = card()
        val url = field("Server address").apply { setText(base); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val user = field("Username"); val pass = field("Password", true)
        c.addView(url); c.addView(user); c.addView(pass)
        c.addView(btn("Log in") {
            base = url.text.toString().trim().trimEnd('/')
            http("/login", JSONObject().put("username", user.text.toString().trim()).put("password", pass.text.toString())) { code, t ->
                val j = JSONObject(t)
                if (code != 200) toast(j.optString("error", "Login failed"))
                else if (j.getString("role") != "agent") toast("Use an agent account in this app.")
                else {
                    token = j.getString("token"); agentName = j.optString("name")
                    prefs.edit().putString("base", base).putString("token", token).putString("name", agentName).apply(); loadLeads()
                }
            }
        })
        body.addView(c)
    }

    private fun loadLeads() {
        http("/dispositions", null) { c0, t0 -> if (c0 == 200) dispositions = JSONArray(t0).let { a -> List(a.length()) { a.getString(it) } } }
        http("/my/leads", null) { code, t ->
            if (code == 401) { logout(); return@http }
            leads = JSONArray(t); idx = 0; homeUi()
        }
    }

    private fun logout() { prefs.edit().remove("token").apply(); token = ""; loginUi() }

    // After login: only the NEXT number is shown, not the whole list
    private fun homeUi() {
        screen("Hi, ${agentName.ifEmpty { "Agent" }}", "${leads.length()} leads waiting")
        val c = card()
        if (leads.length() == 0) {
            c.addView(tv("You're all caught up", 22f, ink, true))
            c.addView(tv("No pending leads. Ask your admin to assign a new list, then tap Refresh.", 15f, mute))
            body.addView(c)
        } else {
            val l = leads.getJSONObject(idx)
            c.addView(tv("NEXT TO CALL", 12f, mute, true))
            c.addView(tv(l.optString("name").ifEmpty { "Lead" }, 26f, ink, true).apply { setPadding(0, dp(8), 0, 0) })
            c.addView(tv(l.getString("phone"), 24f, brand, true))
            c.addView(tv(l.optString("campaign"), 13f, mute).apply { setPadding(0, dp(6), 0, 0) })
            body.addView(c)
            body.addView(btn("Start calling") { startCalling() })
        }
        body.addView(btn("Refresh", soft, brand) { loadLeads() })
        body.addView(btn("Log out", soft, mute) { logout() })
    }

    private fun startCalling() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 1); return
        }
        dialNext()
    }
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) dialNext() else toast("Allow phone calls to start dialing.")
    }

    private fun dialNext() {
        if (idx >= leads.length()) { toast("All leads done."); loadLeads(); return }
        val l = leads.getJSONObject(idx)
        screen("Calling…", "Lead ${idx + 1} of ${leads.length()}")
        val c = card()
        c.addView(tv(l.optString("name").ifEmpty { "Lead" }, 26f, ink, true))
        c.addView(tv(l.getString("phone"), 24f, brand, true))
        c.addView(tv("When the call ends, add the outcome and a remark.", 14f, mute).apply { setPadding(0, dp(12), 0, 0) })
        body.addView(c)
        dialing = true; callStart = System.currentTimeMillis()
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + l.getString("phone"))))
    }

    private fun remarkDialog() {
        val l = leads.getJSONObject(idx)
        var selected: String? = null; var followup = ""
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        box.addView(tv("How did the call go?", 15f, mute))
        val followBtn = btn("Pick follow-up date (optional)", soft, brand) {}.apply { visibility = View.GONE }
        val chips = mutableMapOf<String, TextView>()
        fun paint() {
            chips.forEach { (k, v) -> val on = k == selected; v.background = shape(if (on) brand else Color.WHITE, 22, line); v.setTextColor(if (on) Color.WHITE else ink) }
            followBtn.visibility = if (selected == "Call Back") View.VISIBLE else View.GONE
        }
        dispositions.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { d ->
                val chip = tv(d, 15f).apply {
                    gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(12))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                    setOnClickListener { selected = d; paint() }
                }
                chips[d] = chip; row.addView(chip)
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            box.addView(row)
        }
        box.addView(followBtn)
        followBtn.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                followup = String.format("%04d-%02d-%02d", y, m + 1, d); followBtn.text = "Follow-up: $followup"
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        val remark = field("Remark (required)").apply { minLines = 3; gravity = Gravity.TOP }
        box.addView(remark); paint()
        val d = AlertDialog.Builder(this).setTitle("${l.optString("name")}  ${l.getString("phone")}")
            .setView(ScrollView(this).apply { addView(box) }).setCancelable(false)
            .setPositiveButton("Save and call next", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val sel = selected
            if (sel == null) { toast("Pick how the call went."); return@setOnClickListener }
            if (remark.text.isBlank()) { toast("Add a remark first."); return@setOnClickListener }
            val bodyJson = JSONObject().put("disposition", sel).put("remark", remark.text.toString().trim())
                .put("followup", followup).put("duration", ((System.currentTimeMillis() - callStart) / 1000).toInt())
            http("/leads/${l.getInt("id")}/remark", bodyJson) { code, t ->
                if (code == 200) { d.dismiss(); idx++; root.postDelayed({ dialNext() }, 1200) }
                else toast(JSONObject(t).optString("error", "Could not save. Try again."))
            }
        }
    }
}

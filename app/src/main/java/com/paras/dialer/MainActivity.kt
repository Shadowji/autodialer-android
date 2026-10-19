package com.paras.dialer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private var base = ""; private var token = ""
    private var leads = JSONArray(); private var idx = 0
    private var dispositions = listOf("Interested", "Not Interested", "Call Back", "Wrong Number", "Not Reachable", "Already Done")
    private var dialing = false; private var callStart = 0L
    private val prefs by lazy { getSharedPreferences("dialer", 0) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 48) }
        setContentView(ScrollView(this).apply { addView(root) })
        base = prefs.getString("base", "") ?: ""; token = prefs.getString("token", "") ?: ""
        if (token.isEmpty()) loginUi() else loadLeads()
    }

    // Call ended -> the dialer closes and our app returns to the front -> ask for the remark
    override fun onResume() {
        super.onResume()
        if (dialing) { dialing = false; remarkDialog() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun text(t: String, size: Float = 16f) = TextView(this).apply { text = t; textSize = size; setPadding(0, 12, 0, 12) }
    private fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

    private fun http(path: String, body: JSONObject?, cb: (Int, String) -> Unit) = Thread {
        try {
            val c = URL("$base/api$path").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("Content-Type", "application/json")
            if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
            runOnUiThread { cb(code, txt) }
        } catch (e: Exception) { runOnUiThread { toast("Can't reach the server. Check the server address and your internet.") } }
    }.start()

    private fun loginUi() {
        root.removeAllViews()
        val url = EditText(this).apply { hint = "Server address (e.g. http://192.168.1.5:3000)"; setText(base) }
        val user = EditText(this).apply { hint = "Username" }
        val pass = EditText(this).apply { hint = "Password"; inputType = 129 }
        root.addView(text("Agent login", 26f)); root.addView(url); root.addView(user); root.addView(pass)
        root.addView(button("Log in") {
            base = url.text.toString().trim().trimEnd('/')
            http("/login", JSONObject().put("username", user.text.toString().trim()).put("password", pass.text.toString())) { code, t ->
                val j = JSONObject(t)
                if (code != 200) toast(j.optString("error", "Login failed"))
                else if (j.getString("role") != "agent") toast("Use an agent account in this app.")
                else { token = j.getString("token"); prefs.edit().putString("base", base).putString("token", token).apply(); loadLeads() }
            }
        })
    }

    private fun loadLeads() {
        http("/dispositions", null) { c0, t0 -> if (c0 == 200) dispositions = JSONArray(t0).let { a -> List(a.length()) { a.getString(it) } } }
        http("/my/leads", null) { code, t ->
            if (code == 401) { prefs.edit().remove("token").apply(); token = ""; loginUi(); return@http }
            leads = JSONArray(t); idx = 0; leadsUi()
        }
    }

    private fun leadsUi() {
        root.removeAllViews()
        root.addView(text("Your leads", 26f))
        root.addView(text(if (leads.length() == 0) "No pending leads. Ask your admin to assign a list." else "${leads.length()} leads waiting"))
        if (leads.length() > 0) root.addView(button("Start calling") { startCalling() })
        root.addView(button("Refresh") { loadLeads() })
        root.addView(button("Log out") { prefs.edit().remove("token").apply(); token = ""; loginUi() })
        for (i in 0 until minOf(leads.length(), 100)) leads.getJSONObject(i).let { root.addView(text("${it.optString("name")}  ${it.getString("phone")}", 15f)) }
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
        root.removeAllViews()
        root.addView(text("Calling ${idx + 1} of ${leads.length()}", 16f))
        root.addView(text(l.optString("name").ifEmpty { "Lead" }, 26f)); root.addView(text(l.getString("phone"), 22f))
        dialing = true; callStart = System.currentTimeMillis()
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + l.getString("phone"))))
    }

    private fun remarkDialog() {
        val l = leads.getJSONObject(idx)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val spin = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, dispositions) }
        val remark = EditText(this).apply { hint = "Remark (required)"; minLines = 2 }
        val follow = EditText(this).apply { hint = "Follow-up date, YYYY-MM-DD (optional)" }
        box.addView(spin); box.addView(remark); box.addView(follow)
        val d = AlertDialog.Builder(this).setTitle("${l.optString("name")} ${l.getString("phone")}").setView(box)
            .setCancelable(false).setPositiveButton("Save and call next", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (remark.text.isBlank()) { toast("Add a remark first."); return@setOnClickListener }
            val body = JSONObject().put("disposition", spin.selectedItem.toString()).put("remark", remark.text.toString().trim())
                .put("followup", follow.text.toString().trim()).put("duration", ((System.currentTimeMillis() - callStart) / 1000).toInt())
            http("/leads/${l.getInt("id")}/remark", body) { code, t ->
                if (code == 200) { d.dismiss(); idx++; root.postDelayed({ dialNext() }, 1500) }
                else toast(JSONObject(t).optString("error", "Could not save. Try again."))
            }
        }
    }
}

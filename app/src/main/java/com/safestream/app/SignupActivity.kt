package com.safestream.app

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SignupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        val name = findViewById<EditText>(R.id.etSignupName)
        val email = findViewById<EditText>(R.id.etSignupEmail)
        val password = findViewById<EditText>(R.id.etSignupPassword)
        val confirm = findViewById<EditText>(R.id.etSignupConfirm)
        val signupButton = findViewById<Button>(R.id.btnSignup)
        val loginLink = findViewById<TextView>(R.id.tvGoLogin)

        val prefs = getSharedPreferences("SafeStreamUsers", MODE_PRIVATE)

        signupButton.setOnClickListener {
            val nameText = name.text.toString().trim()
            val emailText = email.text.toString().trim().lowercase()
            val passwordText = password.text.toString()
            val confirmText = confirm.text.toString()

            if (nameText.isEmpty()) {
                name.error = "Enter your name"
                return@setOnClickListener
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(emailText).matches()) {
                email.error = "Enter a valid email"
                return@setOnClickListener
            }

            if (passwordText.length < 6) {
                password.error = "Password must have at least 6 characters"
                return@setOnClickListener
            }

            if (passwordText != confirmText) {
                confirm.error = "Passwords do not match"
                return@setOnClickListener
            }

            if (prefs.contains("user_$emailText")) {
                email.error = "Account already exists. Please log in."
                return@setOnClickListener
            }

            // Store only a salted hash, never the plain-text password
            prefs.edit()
                .putString("name_$emailText", nameText)
                .putString("user_$emailText", hashPassword(passwordText, emailText))
                .apply()

            Toast.makeText(this, "Account created successfully! Please log in.", Toast.LENGTH_LONG).show()

            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        loginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    // PBKDF2 hash. Must be identical to the one in LoginActivity.
    private fun hashPassword(password: String, salt: String): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 10000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }
}
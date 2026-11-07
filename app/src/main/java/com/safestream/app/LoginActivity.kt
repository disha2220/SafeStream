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

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Always show the login page when the app is opened.
        // Do not automatically skip to MainActivity based on a saved session.
        setContentView(R.layout.activity_login)

        val email = findViewById<EditText>(R.id.etLoginEmail)
        val password = findViewById<EditText>(R.id.etLoginPassword)
        val loginButton = findViewById<Button>(R.id.btnLogin)
        val signupLink = findViewById<TextView>(R.id.tvGoSignup)

        val prefs = getSharedPreferences("SafeStreamUsers", MODE_PRIVATE)

        loginButton.setOnClickListener {
            val emailText = email.text.toString().trim().lowercase()
            val passwordText = password.text.toString()

            if (!Patterns.EMAIL_ADDRESS.matcher(emailText).matches()) {
                email.error = "Enter a valid email address"
                return@setOnClickListener
            }

            if (passwordText.isBlank()) {
                password.error = "Enter your password"
                return@setOnClickListener
            }

            val savedHash = prefs.getString("user_$emailText", null)
            if (savedHash == null) {
                email.error = "Account not found. Please sign up first."
                return@setOnClickListener
            }

            if (savedHash != hashPassword(passwordText, emailText)) {
                password.error = "Incorrect password"
                return@setOnClickListener
            }

            // Save the signed-in user only after successful credential validation.
            getSharedPreferences("SafeStreamSession", MODE_PRIVATE)
                .edit()
                .putString("current_user", emailText)
                .apply()

            Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        signupLink.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    // Must match the password-hashing function in SignupActivity.
    private fun hashPassword(password: String, salt: String): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 10000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }
}
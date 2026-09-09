package ru.hyperplanet.lmodel.studio

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.hyperplanet.lmodel.studio.databinding.ActivitySecretEasterBinding

class SecretEasterActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecretEasterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecretEasterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "…"

        binding.etSecret.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                onSubmit()
                true
            } else false
        }
        binding.btnSecretEnter.setOnClickListener { onSubmit() }
    }

    private fun onSubmit() {
        val text = binding.etSecret.text.toString().trim()
        when {
            text.equals("one more time", ignoreCase = true) -> {
                binding.tvSecretOut.text = "more?\nno!\ngoodbye"
                lockInput()
                Handler(Looper.getMainLooper()).postDelayed({
                    finishAffinity()
                }, 1600)
            }
            text.equals("through updates", ignoreCase = true) -> {
                binding.tvSecretOut.text = buildString {
                    appendLine("very, very big update")
                    appendLine("LModel Studio v1.5")
                    appendLine("and when you delete this app,")
                    appendLine("your models and bots will save")
                    appendLine("through updates...")
                    append("return to dev mode")
                }
                lockInput()
                Handler(Looper.getMainLooper()).postDelayed({
                    // Вернуться в Dev Mode, не вылетать из приложения
                    finish()
                }, 2800)
            }
            else -> {
                Toast.makeText(this, "Неверный код доступа.", Toast.LENGTH_SHORT).show()
                binding.etSecret.setText("")
            }
        }
    }

    private fun lockInput() {
        binding.etSecret.isEnabled = false
        binding.btnSecretEnter.isEnabled = false
    }
}

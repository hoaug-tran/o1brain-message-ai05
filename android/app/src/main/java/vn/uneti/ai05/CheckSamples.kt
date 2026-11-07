package vn.uneti.ai05

import java.util.UUID
import kotlin.random.Random

internal object CheckSamples {
    fun next(isSpam: Boolean, current: String): String {
        val code = UUID.randomUUID().toString().take(8).uppercase(java.util.Locale.ROOT)
        val hour = Random.nextInt(1, 13)
        val minute = listOf("00", "15", "30", "45").random()
        val amount = Random.nextInt(10, 500) * 10
        val person = listOf("Alex", "Sam", "Morgan", "Taylor", "Jamie", "Jordan").random()
        val place =
            listOf("library", "cafe", "station", "office", "bookshop", "sports center").random()
        var sample =
            if (isSpam)
                when (Random.nextInt(5)) {
                    0 ->
                        "Congratulations! You have won a FREE prize worth $$amount. Reply WIN $code to claim now!"
                    1 ->
                        "FREE entry in our weekly cash draw! Claim your $$amount reward at https://prize-$code.example."
                    2 ->
                        "URGENT: Your account will be suspended. Confirm your password at https://verify-$code.example."
                    3 ->
                        "Your parcel has an unpaid fee. Update your payment details at https://parcel-$code.example to release it."
                    else ->
                        "WINNER! Collect your $$amount cash prize today. Reply CLAIM $code before this offer expires!"
                }
            else
                when (Random.nextInt(5)) {
                    0 ->
                        "Hi $person, can we meet outside the $place at $hour:$minute? Booking reference $code."
                    1 ->
                        "Your verification code is ${Random.nextInt(100000, 1000000)}. Do not share this code. Request ID $code."
                    2 -> "Your order #$code has shipped. You can check its status in your account."
                    3 ->
                        "Reminder: Your appointment is at $hour:$minute tomorrow. Your booking number is $code."
                    else ->
                        "Hi $person, I left your book at the $place. The collection reference is $code. See you tomorrow!"
                }
        if (sample == current) sample += " Reference " + UUID.randomUUID().toString().take(8)
        return sample
    }
}

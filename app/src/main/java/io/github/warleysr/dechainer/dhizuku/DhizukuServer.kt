package io.github.warleysr.dechainer.dhizuku

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.rosan.dhizuku.aidl.IDhizukuClient
import com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener
import com.rosan.dhizuku.server_api.DhizukuProvider
import com.rosan.dhizuku.server_api.DhizukuService
import com.rosan.dhizuku.shared.DhizukuVariables
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.DeviceAdmin
import io.github.warleysr.dechainer.screens.common.RecoveryConfirmDialog
import io.github.warleysr.dechainer.security.SecurityManager
import io.github.warleysr.dechainer.ui.theme.DechainerTheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private const val TAG = "DhizukuServer"

/** Permissions a Dhizuku client can be granted; one approval grants all of them, like MDPC. */
val DhizukuPermissions = listOf("remote_transact", "remote_process", "user_service", "delegated_scopes", "other")

data class DhizukuClient(
    val uid: Int,
    val packageName: String,
    val signature: String?,
    val permissions: List<String>
)

/** Whether the server is on and which apps may use it, kept in plain preferences. */
object DhizukuStore {
    private const val PREFS = "dhizuku_server"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CLIENTS = "clients"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun clients(context: Context): List<DhizukuClient> {
        val json = prefs(context).getString(KEY_CLIENTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val perms = o.getJSONArray("permissions")
                DhizukuClient(
                    uid = o.getInt("uid"),
                    packageName = o.getString("package"),
                    signature = if (o.isNull("signature")) null else o.getString("signature"),
                    permissions = (0 until perms.length()).map { perms.getString(it) }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read clients", e)
            emptyList()
        }
    }

    private fun save(context: Context, list: List<DhizukuClient>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("uid", it.uid)
                    .put("package", it.packageName)
                    .put("signature", it.signature)
                    .put("permissions", JSONArray(it.permissions))
            )
        }
        prefs(context).edit().putString(KEY_CLIENTS, array.toString()).apply()
    }

    fun setClient(context: Context, client: DhizukuClient) {
        save(context, clients(context).filter { it.uid != client.uid } + client)
    }

    fun remove(context: Context, uid: Int) {
        save(context, clients(context).filter { it.uid != uid })
    }

    fun hasPermission(context: Context, uid: Int, signature: String?, permission: String): Boolean =
        clients(context).any {
            it.uid == uid && it.signature == signature && permission in it.permissions
        }
}

private fun packageSignature(info: PackageInfo): String? {
    @Suppress("DEPRECATION")
    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return signatures?.firstOrNull()?.toByteArray()
        ?.let { MessageDigest.getInstance("SHA-256").digest(it) }
        ?.joinToString("") { "%02x".format(it) }
}

private fun Context.signatureInfo(packageName: String): PackageInfo =
    packageManager.getPackageInfo(
        packageName,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    )

/** Exposes Déchaîner's device owner to apps the user approved, so no second device owner is needed. */
class DechainerDhizukuProvider : DhizukuProvider() {
    override fun onCreateService(client: IDhizukuClient): DhizukuService? {
        val ctx = context ?: return null
        Log.d(TAG, "Creating Dhizuku service")
        return if (DhizukuStore.isEnabled(ctx)) DechainerDhizukuService(ctx, DeviceAdmin.component, client) else null
    }
}

class DechainerDhizukuService(context: Context, admin: android.content.ComponentName, client: IDhizukuClient) :
    DhizukuService(context, admin, client) {

    override fun checkCallingPermission(func: String?, callingUid: Int, callingPid: Int): Boolean {
        if (!DhizukuStore.isEnabled(mContext)) return false
        val packageName = mContext.packageManager.getNameForUid(callingUid) ?: return false
        val signature = try {
            packageSignature(mContext.signatureInfo(packageName))
        } catch (e: Exception) {
            return false
        }
        val required = when (func) {
            "remote_transact", "remote_process" -> func
            "bind_user_service", "unbind_user_service" -> "user_service"
            "get_delegated_scopes", "set_delegated_scopes" -> "delegated_scopes"
            else -> "other"
        }
        val allowed = DhizukuStore.hasPermission(mContext, callingUid, signature, required)
        Log.d(TAG, "UID $callingUid asks for $required: $allowed")
        return allowed
    }

    override fun getVersionName() = "1.0"
}

/** Approval screen: allowing a client always needs the recovery code, so it cannot be done on impulse. */
class DhizukuRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DhizukuStore.isEnabled(this)) {
            finish()
            return
        }
        val bundle = intent.extras ?: run { finish(); return }
        val uid = bundle.getInt(DhizukuVariables.PARAM_CLIENT_UID, -1)
        if (uid == -1) { finish(); return }
        val binder = bundle.getBinder(DhizukuVariables.PARAM_CLIENT_REQUEST_PERMISSION_BINDER)
            ?: run { finish(); return }
        val listener = IDhizukuRequestPermissionListener.Stub.asInterface(binder)
        val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: run { finish(); return }
        val signature = packageSignature(signatureInfo(packageName))
        val label = packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()

        fun close(grant: Boolean) {
            DhizukuStore.setClient(
                this,
                DhizukuClient(uid, packageName, signature, if (grant) DhizukuPermissions else emptyList())
            )
            finish()
            listener.onRequestPermission(
                if (grant) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            )
        }

        setContent {
            DechainerTheme {
                var askCode by rememberSaveable { mutableStateOf(false) }
                if (!askCode) {
                    var seconds by remember { mutableIntStateOf(5) }
                    LaunchedEffect(Unit) {
                        for (i in 4 downTo 0) {
                            delay(1000)
                            seconds = i
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { close(false) },
                        title = { Text(stringResource(R.string.dhizuku_request_title)) },
                        text = { Text("$label\n($packageName)") },
                        confirmButton = {
                            TextButton(onClick = { askCode = true }, enabled = seconds == 0) {
                                val wait = if (seconds > 0) " ($seconds)" else ""
                                Text(stringResource(R.string.dhizuku_allow) + wait)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { close(false) }) { Text(stringResource(R.string.dhizuku_reject)) }
                        }
                    )
                } else {
                    RecoveryConfirmDialog(
                        onConfirm = { code ->
                            val stored = SecurityManager.getRecoveryCode(this@DhizukuRequestActivity)
                            if (stored == null || SecurityManager.validateRecoveryCode(code, stored)) {
                                close(true)
                                true
                            } else false
                        },
                        onDismiss = { close(false) }
                    )
                }
            }
        }
    }
}

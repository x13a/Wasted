package me.lucky.wasted.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.p2p.network.P2PNetwork

/**
 * Fragment showing list of connected P2P devices.
 * Real-time status updates via StateFlow from P2PNetwork.
 */
class ConnectedDevicesFragment : Fragment() {
    
    companion object {
        private const val TAG = "ConnectedDevicesUI"
    }
    
    private lateinit var p2pNetwork: P2PNetwork
    private lateinit var devicesAdapter: DeviceListAdapter
    private lateinit var emptyStateView: TextView
    private lateinit var devicesRecyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = LinearLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        val titleView = TextView(requireContext()).apply {
            text = "Connected Devices"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 18f
        }
        root.addView(titleView)
        
        emptyStateView = TextView(requireContext()).apply {
            text = "No connected devices\nWaiting for peers..."
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 14f
        }
        root.addView(emptyStateView)
        
        devicesRecyclerView = RecyclerView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context)
            visibility = View.GONE
        }
        root.addView(devicesRecyclerView)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize adapter
        devicesAdapter = DeviceListAdapter(
            onDeviceClick = { peer -> showDeviceControl(peer) },
            onResetClick = { peer -> initiateRemoteReset(peer) }
        )
        devicesRecyclerView.adapter = devicesAdapter
        
        // Observe connected peers from P2PNetwork
        viewLifecycleOwner.lifecycleScope.launch {
            p2pNetwork.connectedPeers.collect { peers ->
                if (peers.isEmpty()) {
                    devicesRecyclerView.visibility = View.GONE
                    emptyStateView.visibility = View.VISIBLE
                } else {
                    devicesRecyclerView.visibility = View.VISIBLE
                    emptyStateView.visibility = View.GONE
                    devicesAdapter.submitList(peers)
                }
            }
        }
    }
    
    private fun showDeviceControl(peer: Peer) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Control: ${peer.deviceName}")
            .setMessage("Device IP: ${peer.ipAddress}\nConnected: ${peer.isConnected}")
            .setPositiveButton("Close", null)
            .create()
        dialog.show()
    }
    
    private fun initiateRemoteReset(peer: Peer) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remote Reset")
            .setMessage("Send reset command to ${peer.deviceName}?")
            .setPositiveButton("Send") { _, _ ->
                // Send reset command via RemoteControlManager
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/**
 * RecyclerView adapter for displaying connected devices list.
 */
class DeviceListAdapter(
    private val onDeviceClick: (Peer) -> Unit,
    private val onResetClick: (Peer) -> Unit
) : ListAdapter<Peer, DeviceViewHolder>(PeerDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LinearLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                120
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
        }
        return DeviceViewHolder(view, onDeviceClick, onResetClick)
    }
    
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

/**
 * ViewHolder for individual device item.
 */
class DeviceViewHolder(
    private val itemView: LinearLayout,
    private val onDeviceClick: (Peer) -> Unit,
    private val onResetClick: (Peer) -> Unit
) : RecyclerView.ViewHolder(itemView) {
    
    private lateinit var deviceName: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var resetButton: Button
    
    init {
        deviceName = TextView(itemView.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            textSize = 16f
        }
        
        connectionStatus = TextView(itemView.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 12f
        }
        
        resetButton = Button(itemView.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "Reset"
        }
        
        itemView.addView(deviceName)
        itemView.addView(connectionStatus)
        itemView.addView(resetButton)
    }
    
    fun bind(peer: Peer) {
        deviceName.text = peer.deviceName
        connectionStatus.text = if (peer.isConnected) "Connected" else "Offline"
        connectionStatus.setTextColor(
            if (peer.isConnected) Color.GREEN else Color.RED
        )
        
        itemView.setOnClickListener { onDeviceClick(peer) }
        resetButton.setOnClickListener { onResetClick(peer) }
    }
}

/**
 * DiffCallback for efficient RecyclerView updates.
 */
class PeerDiffCallback : DiffUtil.ItemCallback<Peer>() {
    override fun areItemsTheSame(oldItem: Peer, newItem: Peer): Boolean =
        oldItem.deviceId == newItem.deviceId
    
    override fun areContentsTheSame(oldItem: Peer, newItem: Peer): Boolean =
        oldItem == newItem
}

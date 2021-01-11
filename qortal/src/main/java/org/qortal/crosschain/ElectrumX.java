package org.qortal.crosschain;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocketFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.TrustlessSSLSocketFactory;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;

/** ElectrumX network support for querying Bitcoiny-related info like block headers, transaction outputs, etc. */
public class ElectrumX extends BitcoinyBlockchainProvider {

	private static final Logger LOGGER = LogManager.getLogger(ElectrumX.class);
	private static final Random RANDOM = new Random();

	private static final double MIN_PROTOCOL_VERSION = 1.2;
	private static final int BLOCK_HEADER_LENGTH = 80;

	// "message": "daemon error: DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})"
	private static final Pattern DAEMON_ERROR_REGEX = Pattern.compile("DaemonError\\(\\{.*'code': ?(-?[0-9]+).*\\}\\)\\z"); // Capture 'code' inside curly-brace content

	/** Error message sent by some ElectrumX servers when they don't support returning verbose transactions. */
	private static final String VERBOSE_TRANSACTIONS_UNSUPPORTED_MESSAGE = "verbose transactions are currently unsupported";

	public static class Server {
		String hostname;

		public enum ConnectionType { TCP, SSL }
		ConnectionType connectionType;

		int port;

		public Server(String hostname, ConnectionType connectionType, int port) {
			this.hostname = hostname;
			this.connectionType = connectionType;
			this.port = port;
		}

		@Override
		public boolean equals(Object other) {
			if (other == this)
				return true;

			if (!(other instanceof Server))
				return false;

			Server otherServer = (Server) other;

			return this.connectionType == otherServer.connectionType
					&& this.port == otherServer.port
					&& this.hostname.equals(otherServer.hostname);
		}

		@Override
		public int hashCode() {
			return this.hostname.hashCode() ^ this.port;
		}

		@Override
		public String toString() {
			return String.format("%s:%s:%d", this.connectionType.name(), this.hostname, this.port);
		}
	}
	private Set<Server> servers = new HashSet<>();
	private List<Server> remainingServers = new ArrayList<>();
	private Set<Server> uselessServers = Collections.synchronizedSet(new HashSet<>());

	private final String netId;
	private final String expectedGenesisHash;
	private final Map<Server.ConnectionType, Integer> defaultPorts = new EnumMap<>(Server.ConnectionType.class);

	private final Object serverLock = new Object();
	private Server currentServer;
	private Socket socket;
	private Scanner scanner;
	private int nextId = 1;

	private static final int TX_CACHE_SIZE = 200;
	@SuppressWarnings("serial")
	private final Map<String, BitcoinyTransaction> transactionCache = Collections.synchronizedMap(new LinkedHashMap<>(TX_CACHE_SIZE + 1, 0.75F, true) {
		// This method is called just after a new entry has been added
		@Override
		public boolean removeEldestEntry(Map.Entry<String, BitcoinyTransaction> eldest) {
			return size() > TX_CACHE_SIZE;
		}
	});

	// Constructors

	public ElectrumX(String netId, String genesisHash, Collection<Server> initialServerList, Map<Server.ConnectionType, Integer> defaultPorts) {
		this.netId = netId;
		this.expectedGenesisHash = genesisHash;
		this.servers.addAll(initialServerList);
		this.defaultPorts.putAll(defaultPorts);
	}

	// Methods for use by other classes

	@Override
	public String getNetId() {
		return this.netId;
	}

	/**
	 * Returns current blockchain height.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public int getCurrentHeight() throws ForeignBlockchainException {
		Object blockObj = this.rpc("blockchain.headers.subscribe");
		if (!(blockObj instanceof JSONObject))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from ElectrumX blockchain.headers.subscribe RPC");

		JSONObject blockJson = (JSONObject) blockObj;

		Object heightObj = blockJson.get("height");

		if (!(heightObj instanceof Long))
			throw new ForeignBlockchainException.NetworkException("Missing/invalid 'height' in JSON from ElectrumX blockchain.headers.subscribe RPC");

		return ((Long) heightObj).intValue();
	}

	/**
	 * Returns list of raw block headers, starting from <tt>startHeight</tt> inclusive.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public List<byte[]> getRawBlockHeaders(int startHeight, int count) throws ForeignBlockchainException {
		Object blockObj = this.rpc("blockchain.block.headers", startHeight, count);
		if (!(blockObj instanceof JSONObject))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from ElectrumX blockchain.block.headers RPC");

		JSONObject blockJson = (JSONObject) blockObj;

		Object countObj = blockJson.get("count");
		Object hexObj = blockJson.get("hex");

		if (!(countObj instanceof Long) || !(hexObj instanceof String))
			throw new ForeignBlockchainException.NetworkException("Missing/invalid 'count' or 'hex' entries in JSON from ElectrumX blockchain.block.headers RPC");

		Long returnedCount = (Long) countObj;
		String hex = (String) hexObj;

		byte[] raw = HashCode.fromString(hex).asBytes();
		if (raw.length != returnedCount * BLOCK_HEADER_LENGTH)
			throw new ForeignBlockchainException.NetworkException("Unexpected raw header length in JSON from ElectrumX blockchain.block.headers RPC");

		List<byte[]> rawBlockHeaders = new ArrayList<>(returnedCount.intValue());
		for (int i = 0; i < returnedCount; ++i)
			rawBlockHeaders.add(Arrays.copyOfRange(raw, i * BLOCK_HEADER_LENGTH, (i + 1) * BLOCK_HEADER_LENGTH));

		return rawBlockHeaders;
	}

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if script unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	@Override
	public long getConfirmedBalance(byte[] script) throws ForeignBlockchainException {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object balanceObj = this.rpc("blockchain.scripthash.get_balance", HashCode.fromBytes(scriptHash).toString());
		if (!(balanceObj instanceof JSONObject))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from ElectrumX blockchain.scripthash.get_balance RPC");

		JSONObject balanceJson = (JSONObject) balanceObj;

		Object confirmedBalanceObj = balanceJson.get("confirmed");

		if (!(confirmedBalanceObj instanceof Long))
			throw new ForeignBlockchainException.NetworkException("Missing confirmed balance from ElectrumX blockchain.scripthash.get_balance RPC");

		return (Long) balanceJson.get("confirmed");
	}

	/**
	 * Returns list of unspent outputs pertaining to passed payment script.
	 * <p>
	 * @return list of unspent outputs, or empty list if script unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	@Override
	public List<UnspentOutput> getUnspentOutputs(byte[] script, boolean includeUnconfirmed) throws ForeignBlockchainException {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object unspentJson = this.rpc("blockchain.scripthash.listunspent", HashCode.fromBytes(scriptHash).toString());
		if (!(unspentJson instanceof JSONArray))
			throw new ForeignBlockchainException("Expected array output from ElectrumX blockchain.scripthash.listunspent RPC");

		List<UnspentOutput> unspentOutputs = new ArrayList<>();
		for (Object rawUnspent : (JSONArray) unspentJson) {
			JSONObject unspent = (JSONObject) rawUnspent;

			int height = ((Long) unspent.get("height")).intValue();
			// We only want unspent outputs from confirmed transactions (and definitely not mempool duplicates with height 0)
			if (!includeUnconfirmed && height <= 0)
				continue;

			byte[] txHash = HashCode.fromString((String) unspent.get("tx_hash")).asBytes();
			int outputIndex = ((Long) unspent.get("tx_pos")).intValue();
			long value = (Long) unspent.get("value");

			unspentOutputs.add(new UnspentOutput(txHash, outputIndex, height, value));
		}

		return unspentOutputs;
	}

	/**
	 * Returns raw transaction for passed transaction hash.
	 * <p>
	 * NOTE: Do not mutate returned byte[]!
	 * 
	 * @throws ForeignBlockchainException.NotFoundException if transaction not found
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public byte[] getRawTransaction(String txHash) throws ForeignBlockchainException {
		Object rawTransactionHex;
		try {
			rawTransactionHex = this.rpc("blockchain.transaction.get", txHash, false);
		} catch (ForeignBlockchainException.NetworkException e) {
			// DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})
			if (Integer.valueOf(-5).equals(e.getDaemonErrorCode()))
				throw new ForeignBlockchainException.NotFoundException(e.getMessage());

			throw e;
		}

		if (!(rawTransactionHex instanceof String))
			throw new ForeignBlockchainException.NetworkException("Expected hex string as raw transaction from ElectrumX blockchain.transaction.get RPC");

		return HashCode.fromString((String) rawTransactionHex).asBytes();
	}

	/**
	 * Returns raw transaction for passed transaction hash.
	 * <p>
	 * NOTE: Do not mutate returned byte[]!
	 * 
	 * @throws ForeignBlockchainException.NotFoundException if transaction not found
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public byte[] getRawTransaction(byte[] txHash) throws ForeignBlockchainException {
		return getRawTransaction(HashCode.fromBytes(txHash).toString());
	}

	/**
	 * Returns transaction info for passed transaction hash.
	 * <p>
	 * @throws ForeignBlockchainException.NotFoundException if transaction not found
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
		// Check cache first
		BitcoinyTransaction transaction = transactionCache.get(txHash);
		if (transaction != null)
			return transaction;

		Object transactionObj = null;

		do {
			try {
				transactionObj = this.rpc("blockchain.transaction.get", txHash, true);
			} catch (ForeignBlockchainException.NetworkException e) {
				// DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})
				if (Integer.valueOf(-5).equals(e.getDaemonErrorCode()))
					throw new ForeignBlockchainException.NotFoundException(e.getMessage());

				// Some servers also return non-standard responses like this:
				// {"error":"verbose transactions are currently unsupported","id":3,"jsonrpc":"2.0"}
				// We should probably not use this server any more
				if (e.getServer() != null && e.getMessage() != null && e.getMessage().contains(VERBOSE_TRANSACTIONS_UNSUPPORTED_MESSAGE)) {
					Server uselessServer = (Server) e.getServer();
					LOGGER.trace(() -> String.format("Server %s doesn't support verbose transactions - barring use of that server", uselessServer));
					this.uselessServers.add(uselessServer);
					this.closeServer(uselessServer);
					continue;
				}

				throw e;
			}
		} while (transactionObj == null);

		if (!(transactionObj instanceof JSONObject))
			throw new ForeignBlockchainException.NetworkException("Expected JSONObject as response from ElectrumX blockchain.transaction.get RPC");

		JSONObject transactionJson = (JSONObject) transactionObj;

		Object inputsObj = transactionJson.get("vin");
		if (!(inputsObj instanceof JSONArray))
			throw new ForeignBlockchainException.NetworkException("Expected JSONArray for 'vin' from ElectrumX blockchain.transaction.get RPC");

		Object outputsObj = transactionJson.get("vout");
		if (!(outputsObj instanceof JSONArray))
			throw new ForeignBlockchainException.NetworkException("Expected JSONArray for 'vout' from ElectrumX blockchain.transaction.get RPC");

		try {
			int size = ((Long) transactionJson.get("size")).intValue();
			int locktime = ((Long) transactionJson.get("locktime")).intValue();

			// Timestamp might not be present, e.g. for unconfirmed transaction
			Object timeObj = transactionJson.get("time");
			Integer timestamp = timeObj != null
					? ((Long) timeObj).intValue()
					: null;

			List<BitcoinyTransaction.Input> inputs = new ArrayList<>();
			for (Object inputObj : (JSONArray) inputsObj) {
				JSONObject inputJson = (JSONObject) inputObj;

				String scriptSig = (String) ((JSONObject) inputJson.get("scriptSig")).get("hex");
				int sequence = ((Long) inputJson.get("sequence")).intValue();
				String outputTxHash = (String) inputJson.get("txid");
				int outputVout = ((Long) inputJson.get("vout")).intValue();

				inputs.add(new BitcoinyTransaction.Input(scriptSig, sequence, outputTxHash, outputVout));
			}

			List<BitcoinyTransaction.Output> outputs = new ArrayList<>();
			for (Object outputObj : (JSONArray) outputsObj) {
				JSONObject outputJson = (JSONObject) outputObj;

				String scriptPubKey = (String) ((JSONObject) outputJson.get("scriptPubKey")).get("hex");
				long value = BigDecimal.valueOf((Double) outputJson.get("value")).setScale(8).unscaledValue().longValue();

				// address too, if present
				List<String> addresses = null;
				Object addressesObj = ((JSONObject) outputJson.get("scriptPubKey")).get("addresses");
				if (addressesObj instanceof JSONArray) {
					addresses = new ArrayList<>();
					for (Object addressObj : (JSONArray) addressesObj)
						addresses.add((String) addressObj);
				}

				outputs.add(new BitcoinyTransaction.Output(scriptPubKey, value, addresses));
			}

			transaction = new BitcoinyTransaction(txHash, size, locktime, timestamp, inputs, outputs);

			// Save into cache
			transactionCache.put(txHash, transaction);

			return transaction;
		} catch (NullPointerException | ClassCastException e) {
			// Unexpected / invalid response from ElectrumX server
		}

		throw new ForeignBlockchainException.NetworkException("Unexpected JSON format from ElectrumX blockchain.transaction.get RPC");
	}

	/**
	 * Returns list of transactions, relating to passed payment script.
	 * <p>
	 * @return list of related transactions, or empty list if script unknown
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public List<TransactionHash> getAddressTransactions(byte[] script, boolean includeUnconfirmed) throws ForeignBlockchainException {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object transactionsJson = this.rpc("blockchain.scripthash.get_history", HashCode.fromBytes(scriptHash).toString());
		if (!(transactionsJson instanceof JSONArray))
			throw new ForeignBlockchainException.NetworkException("Expected array output from ElectrumX blockchain.scripthash.get_history RPC");

		List<TransactionHash> transactionHashes = new ArrayList<>();

		for (Object rawTransactionInfo : (JSONArray) transactionsJson) {
			JSONObject transactionInfo = (JSONObject) rawTransactionInfo;

			Long height = (Long) transactionInfo.get("height");
			if (!includeUnconfirmed && (height == null || height == 0))
				// We only want confirmed transactions
				continue;

			String txHash = (String) transactionInfo.get("tx_hash");

			transactionHashes.add(new TransactionHash(height.intValue(), txHash));
		}

		return transactionHashes;
	}

	/**
	 * Broadcasts raw transaction to network.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public void broadcastTransaction(byte[] transactionBytes) throws ForeignBlockchainException {
		Object rawBroadcastResult = this.rpc("blockchain.transaction.broadcast", HashCode.fromBytes(transactionBytes).toString());

		// We're expecting a simple string that is the transaction hash
		if (!(rawBroadcastResult instanceof String))
			throw new ForeignBlockchainException.NetworkException("Unexpected response from ElectrumX blockchain.transaction.broadcast RPC");
	}

	// Class-private utility methods

	/**
	 * Query current server for its list of peer servers, and return those we can parse.
	 * <p>
	 * @throws ForeignBlockchainException
	 * @throws ClassCastException to be handled by caller
	 */
	private Set<Server> serverPeersSubscribe() throws ForeignBlockchainException {
		Set<Server> newServers = new HashSet<>();

		Object peers = this.connectedRpc("server.peers.subscribe");

		for (Object rawPeer : (JSONArray) peers) {
			JSONArray peer = (JSONArray) rawPeer;
			if (peer.size() < 3)
				// We're expecting at least 3 fields for each peer entry: IP, hostname, features
				continue;

			String hostname = (String) peer.get(1);
			JSONArray features = (JSONArray) peer.get(2);

			for (Object rawFeature : features) {
				String feature = (String) rawFeature;
				Server.ConnectionType connectionType = null;
				Integer port = null;

				switch (feature.charAt(0)) {
					case 's':
						connectionType = Server.ConnectionType.SSL;
						port = this.defaultPorts.get(connectionType);
						break;

					case 't':
						connectionType = Server.ConnectionType.TCP;
						port = this.defaultPorts.get(connectionType);
						break;

					default:
						// e.g. could be 'v' for protocol version, or 'p' for pruning limit
						break;
				}

				if (connectionType == null || port == null)
					// We couldn't extract any peer connection info?
					continue;

				// Possible non-default port?
				if (feature.length() > 1)
					try {
						port = Integer.parseInt(feature.substring(1));
					} catch (NumberFormatException e) {
						// no good
						continue; // for-loop above
					}

				Server newServer = new Server(hostname, connectionType, port);
				newServers.add(newServer);
			}
		}

		return newServers;
	}

	/**
	 * Performs RPC call, with automatic reconnection to different server if needed.
	 * <p>
	 * @return "result" object from within JSON output
	 * @throws ForeignBlockchainException if server returns error or something goes wrong
	 */
	private Object rpc(String method, Object...params) throws ForeignBlockchainException {
		synchronized (this.serverLock) {
			if (this.remainingServers.isEmpty())
				this.remainingServers.addAll(this.servers);

			while (haveConnection()) {
				Object response = connectedRpc(method, params);
				if (response != null)
					return response;

				// Didn't work, try another server...
				this.closeServer();
			}

			// Failed to perform RPC - maybe lack of servers?
			throw new ForeignBlockchainException.NetworkException(String.format("Failed to perform ElectrumX RPC %s", method));
		}
	}

	/** Returns true if we have, or create, a connection to an ElectrumX server. */
	private boolean haveConnection() throws ForeignBlockchainException {
		if (this.currentServer != null)
			return true;

		while (!this.remainingServers.isEmpty()) {
			Server server = this.remainingServers.remove(RANDOM.nextInt(this.remainingServers.size()));
			LOGGER.trace(() -> String.format("Connecting to %s", server));

			try {
				SocketAddress endpoint = new InetSocketAddress(server.hostname, server.port);
				int timeout = 5000; // ms

				this.socket = new Socket();
				this.socket.connect(endpoint, timeout);
				this.socket.setTcpNoDelay(true);

				if (server.connectionType == Server.ConnectionType.SSL) {
					SSLSocketFactory factory = TrustlessSSLSocketFactory.getSocketFactory();
					this.socket = factory.createSocket(this.socket, server.hostname, server.port, true);
				}

				this.scanner = new Scanner(this.socket.getInputStream());
				this.scanner.useDelimiter("\n");

				// Check connection is suitable by asking for server features, including genesis block hash
				JSONObject featuresJson = (JSONObject) this.connectedRpc("server.features");

				if (featuresJson == null || Double.valueOf((String) featuresJson.get("protocol_min")) < MIN_PROTOCOL_VERSION)
					continue;

				if (this.expectedGenesisHash != null && !((String) featuresJson.get("genesis_hash")).equals(this.expectedGenesisHash))
					continue;

				// Ask for more servers
				Set<Server> moreServers = serverPeersSubscribe();
				// Discard duplicate servers we already know
				moreServers.removeAll(this.servers);
				// Add to both lists
				this.remainingServers.addAll(moreServers);
				this.servers.addAll(moreServers);

				LOGGER.debug(() -> String.format("Connected to %s", server));
				this.currentServer = server;
				return true;
			} catch (IOException | ForeignBlockchainException | ClassCastException | NullPointerException e) {
				// Didn't work, try another server...
				closeServer();
			}
		}

		return false;
	}

	/**
	 * Perform RPC using currently connected server.
	 * <p>
	 * @param method
	 * @param params
	 * @return response Object, or null if server fails to respond
	 * @throws ForeignBlockchainException if server returns error
	 */
	@SuppressWarnings("unchecked")
	private Object connectedRpc(String method, Object...params) throws ForeignBlockchainException {
		JSONObject requestJson = new JSONObject();
		requestJson.put("id", this.nextId++);
		requestJson.put("method", method);
		requestJson.put("jsonrpc", "2.0");

		JSONArray requestParams = new JSONArray();
		requestParams.addAll(Arrays.asList(params));
		requestJson.put("params", requestParams);

		String request = requestJson.toJSONString() + "\n";
		LOGGER.trace(() -> String.format("Request: %s", request));

		final String response;

		try {
			this.socket.getOutputStream().write(request.getBytes());
			response = scanner.next();
		} catch (IOException | NoSuchElementException e) {
			// Unable to send, or receive -- try another server?
			return null;
		}

		LOGGER.trace(() -> String.format("Response: %s", response));

		if (response.isEmpty())
			// Empty response - try another server?
			return null;

		Object responseObj = JSONValue.parse(response);
		if (!(responseObj instanceof JSONObject))
			// Unexpected response - try another server?
			return null;

		JSONObject responseJson = (JSONObject) responseObj;

		Object errorObj = responseJson.get("error");
		if (errorObj != null) {
			if (errorObj instanceof String)
				throw new ForeignBlockchainException.NetworkException(String.format("Unexpected error message from ElectrumX RPC %s: %s", method, (String) errorObj), this.currentServer);

			if (!(errorObj instanceof JSONObject))
				throw new ForeignBlockchainException.NetworkException(String.format("Unexpected error response from ElectrumX RPC %s", method), this.currentServer);

			JSONObject errorJson = (JSONObject) errorObj;

			Object messageObj = errorJson.get("message");

			if (!(messageObj instanceof String))
				throw new ForeignBlockchainException.NetworkException(String.format("Missing/invalid message in error response from ElectrumX RPC %s", method), this.currentServer);

			String message = (String) messageObj;

			// Some error 'messages' are actually wrapped upstream bitcoind errors:
			// "message": "daemon error: DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})"
			// We want to detect these and extract the upstream error code for caller's use
			Matcher messageMatcher = DAEMON_ERROR_REGEX.matcher(message);
			if (messageMatcher.find())
				try {
					int daemonErrorCode = Integer.parseInt(messageMatcher.group(1));
					throw new ForeignBlockchainException.NetworkException(daemonErrorCode, message, this.currentServer);
				} catch (NumberFormatException e) {
					// We couldn't parse the error code integer? Fall-through to generic exception...
				}

			throw new ForeignBlockchainException.NetworkException(message, this.currentServer);
		}

		return responseJson.get("result");
	}

	/**
	 * Closes connection to <tt>server</tt> if it is currently connected server.
	 * @param server
	 */
	private void closeServer(Server server) {
		synchronized (this.serverLock) {
			if (this.currentServer == null || !this.currentServer.equals(server))
				return;

			if (this.socket != null && !this.socket.isClosed())
				try {
					this.socket.close();
				} catch (IOException e) {
					// We did try...
				}

			this.socket = null;
			this.scanner = null;
			this.currentServer = null;
		}
	}

	/** Closes connection to currently connected server (if any). */
	private void closeServer() {
		synchronized (this.serverLock) {
			this.closeServer(this.currentServer);
		}
	}

}

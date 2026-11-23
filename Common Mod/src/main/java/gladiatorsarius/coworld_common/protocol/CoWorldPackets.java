package gladiatorsarius.coworld_common.protocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CoWorldPackets {
	public static final int HELLO_PAYLOAD_LIMIT = 64 * 1024 * 1024;
	public static final int CACHE_MANIFEST_LIMIT = 32 * 1024 * 1024;
	public static final int MAX_MANIFEST_ENTRIES = 100_000;
	public static final int MAX_STRING_BYTES = 128;

	public record ManifestEntry(int rootKind, String path, long storedLength, byte[] sha256) {
		public ManifestEntry {
			validateRootKind(rootKind);
			if (path == null || path.isEmpty()) {
				throw new IllegalArgumentException("manifest path is required");
			}
			validateManifestPath(path);
			if (storedLength < 0 || sha256 == null || sha256.length != 32) {
				throw new IllegalArgumentException("invalid manifest entry");
			}
			sha256 = sha256.clone();
		}

		@Override
		public byte[] sha256() {
			return sha256.clone();
		}
	}

	public record CacheDeclaration(UUID serverUuid, UUID worldUuid, String coworldVersion,
								   String minecraftVersion, int cacheFormatVersion, int hashFormatVersion,
								   String schemaId, int schemaVersion, List<ManifestEntry> manifest) {
		public CacheDeclaration {
			if (serverUuid == null || worldUuid == null) {
				throw new IllegalArgumentException("cache UUIDs are required");
			}
			validateReleaseVersion(coworldVersion);
			validateReleaseVersion(minecraftVersion);
			if (cacheFormatVersion < 0 || hashFormatVersion < 0) {
				throw new IllegalArgumentException("invalid cache format version");
			}
			validateSchema(schemaId, schemaVersion);
			if (manifest == null || manifest.size() > MAX_MANIFEST_ENTRIES) {
				throw new IllegalArgumentException("invalid manifest");
			}
			java.util.HashSet<String> normalizedPaths = new java.util.HashSet<>();
			for (ManifestEntry entry : manifest) {
				String key = entry.rootKind() + ":" + platformAlias(entry.path());
				if (!normalizedPaths.add(key)) {
					throw new IllegalArgumentException("duplicate or platform-colliding manifest path");
				}
			}
			manifest = List.copyOf(manifest);
		}
	}

	public record Hello(String releaseVersion, int protocolVersion, boolean offloading,
						long diskAllowance, int simulationDistance, String schemaId, int schemaVersion,
						CacheDeclaration cache, boolean valid) implements CustomPacketPayload {
		public static final Identifier ID = Identifier.fromNamespaceAndPath("coworld", "hello");
		public static final Type<Hello> TYPE = new Type<>(ID);
		public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(Hello::encode, Hello::decodeLenient);

		public Hello(String releaseVersion, int protocolVersion, boolean offloading,
					 long diskAllowance, int simulationDistance, String schemaId, int schemaVersion,
					 CacheDeclaration cache) {
			this(releaseVersion, protocolVersion, offloading, diskAllowance, simulationDistance,
				schemaId, schemaVersion, cache, true);
		}

		public Hello {
			if (valid) {
				validateReleaseVersion(releaseVersion);
				if (!ProtocolVersion.isSupported(protocolVersion)) {
					throw new IllegalArgumentException("unsupported protocol");
				}
				if (diskAllowance < 0 || simulationDistance < 0) {
					throw new IllegalArgumentException("negative integer");
				}
				validateSchema(schemaId, schemaVersion);
			}
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}

		private static void encode(FriendlyByteBuf buf, Hello value) {
			writeIntLengthUtf(buf, value.releaseVersion, 32);
			buf.writeInt(value.protocolVersion);
			buf.writeBoolean(value.offloading);
			buf.writeLong(value.diskAllowance);
			buf.writeInt(value.simulationDistance);
			writeNullableUtf(buf, value.schemaId, 128);
			buf.writeInt(value.schemaVersion);
			if (value.cache == null) {
				buf.writeByte(0);
			} else {
				buf.writeByte(1);
				writeCache(buf, value.cache);
			}
		}

		private static Hello decodeLenient(FriendlyByteBuf buf) {
			try {
				return decodeValidated(buf);
			} catch (RuntimeException | CharacterCodingException exception) {
				if (buf.isReadable()) buf.skipBytes(buf.readableBytes());
				return new Hello("invalid", ProtocolVersion.V1, false, 0, 0, null, 0, null, false);
			}
		}

		private static Hello decodeValidated(FriendlyByteBuf buf) throws CharacterCodingException {
			if (buf.readableBytes() > HELLO_PAYLOAD_LIMIT) {
				throw new IllegalArgumentException("Hello payload exceeds 64 MiB");
			}
			String release = readIntLengthUtf(buf, 32);
			int protocol = buf.readInt();
			boolean offloading = buf.readBoolean();
			long allowance = buf.readLong();
			int distance = buf.readInt();
			String schema = readNullableUtf(buf, 128);
			int schemaVersion = buf.readInt();
			int cachePresence = buf.readUnsignedByte();
			CacheDeclaration cache = switch (cachePresence) {
				case 0 -> null;
				case 1 -> readCache(buf);
				default -> throw new IllegalArgumentException("invalid cache presence");
			};
			if (buf.isReadable()) {
				throw new IllegalArgumentException("trailing Hello bytes");
			}
			return new Hello(release, protocol, offloading, allowance, distance, schema, schemaVersion, cache, true);
		}
	}

	public record PingProbe(long nonce, int sequence) implements CustomPacketPayload {
		public static final Identifier ID = Identifier.fromNamespaceAndPath("coworld", "ping_probe");
		public static final Type<PingProbe> TYPE = new Type<>(ID);
		public static final StreamCodec<FriendlyByteBuf, PingProbe> CODEC = StreamCodec.of(
			(buf, value) -> buf.writeLong(value.nonce).writeByte(value.sequence),
			buf -> {
				long nonce = buf.readLong();
				int sequence = buf.readUnsignedByte();
				if (sequence < 1 || sequence > 3) throw new IllegalArgumentException("invalid probe sequence");
				if (buf.isReadable()) throw new IllegalArgumentException("trailing probe bytes");
				return new PingProbe(nonce, sequence);
			});
		public PingProbe {
			if (sequence < 1 || sequence > 3) throw new IllegalArgumentException("invalid probe sequence");
		}
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record PingEcho(long nonce, int sequence, boolean valid) implements CustomPacketPayload {
		public static final Identifier ID = Identifier.fromNamespaceAndPath("coworld", "ping_echo");
		public static final Type<PingEcho> TYPE = new Type<>(ID);
		public static final StreamCodec<FriendlyByteBuf, PingEcho> CODEC = StreamCodec.of(
			(buf, value) -> buf.writeLong(value.nonce).writeByte(value.sequence),
			buf -> {
				try {
					long nonce = buf.readLong();
					int sequence = buf.readUnsignedByte();
					if (sequence < 1 || sequence > 3) throw new IllegalArgumentException("invalid echo sequence");
					if (buf.isReadable()) throw new IllegalArgumentException("trailing echo bytes");
					return new PingEcho(nonce, sequence);
				} catch (RuntimeException exception) {
					if (buf.isReadable()) buf.skipBytes(buf.readableBytes());
					return new PingEcho(0, 1, false);
				}
			});
		public PingEcho(long nonce, int sequence) {
			this(nonce, sequence, true);
		}
		public PingEcho {
			if (valid && (sequence < 1 || sequence > 3)) throw new IllegalArgumentException("invalid echo sequence");
		}
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record Accept(int protocolVersion, int requestedRadius, long syncSpeedLimit, UUID sessionId) implements CustomPacketPayload {
		public static final Identifier ID = Identifier.fromNamespaceAndPath("coworld", "accept");
		public static final Type<Accept> TYPE = new Type<>(ID);
		public static final StreamCodec<FriendlyByteBuf, Accept> CODEC = StreamCodec.of(
			(buf, value) -> buf.writeInt(value.protocolVersion).writeInt(value.requestedRadius).writeLong(value.syncSpeedLimit).writeUUID(value.sessionId),
			buf -> {
				Accept value = new Accept(buf.readInt(), buf.readInt(), buf.readLong(), buf.readUUID());
				if (buf.isReadable()) throw new IllegalArgumentException("trailing accept bytes");
				return value;
			});
		public Accept {
			if (!ProtocolVersion.isSupported(protocolVersion) || requestedRadius < 0 || syncSpeedLimit < 0 || sessionId == null) {
				throw new IllegalArgumentException("invalid accept");
			}
		}
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record Decline(int reason) implements CustomPacketPayload {
		public static final int OFFLOADING_DISABLED = 1;
		public static final int CLIENT_OPTED_OUT = 2;
		public static final int CAPACITY_REACHED = 3;
		public static final int PING_ABOVE_CAP = 4;
		public static final int INCOMPATIBLE_VERSIONS = 5;
		public static final int FAILED_NEGOTIATION = 6;
		public static final Identifier ID = Identifier.fromNamespaceAndPath("coworld", "decline");
		public static final Type<Decline> TYPE = new Type<>(ID);
		public static final StreamCodec<FriendlyByteBuf, Decline> CODEC = StreamCodec.of(
			(buf, value) -> buf.writeByte(value.reason),
			buf -> {
				Decline value = new Decline(buf.readUnsignedByte());
				if (buf.isReadable()) throw new IllegalArgumentException("trailing decline bytes");
				return value;
			});
		public Decline {
			if (reason < 1 || reason > 6) throw new IllegalArgumentException("invalid decline reason");
		}
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public static void validateRootKind(int rootKind) {
		if (rootKind < 1 || rootKind > 4) throw new IllegalArgumentException("invalid manifest root");
	}

	public static void validateManifestPath(String path) {
		if (path.contains("\\") || path.startsWith("/") || path.startsWith("~")) {
			throw new IllegalArgumentException("invalid manifest path");
		}
		if (path.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("NUL manifest path");
		}
		String[] segments = path.split("/", -1);
		for (String segment : segments) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
				|| segment.indexOf(':') >= 0) {
				throw new IllegalArgumentException("invalid manifest path");
			}
		}
		if (encodedLength(path) > 4096) throw new IllegalArgumentException("manifest path is too long");
	}

	private static String platformAlias(String path) {
		String normalized = path.toLowerCase(java.util.Locale.ROOT);
		String[] segments = normalized.split("/", -1);
		for (int i = 0; i < segments.length; i++) {
			segments[i] = segments[i].replaceAll("[. ]+$", "");
		}
		return String.join("/", segments);
	}

	public static void validateReleaseVersion(String version) {
		if (version == null || version.isEmpty() || encodedLength(version) > 32) {
			throw new IllegalArgumentException("invalid release version");
		}
	}

	public static void validateSchema(String schemaId, int schemaVersion) {
		if (schemaVersion < 0) throw new IllegalArgumentException("negative schema version");
		if (schemaId == null) {
			if (schemaVersion != 0) throw new IllegalArgumentException("schema version requires schema ID");
		} else {
			if (schemaId.isEmpty() || encodedLength(schemaId) > 128) throw new IllegalArgumentException("invalid schema ID");
		}
	}

	private static int encodedLength(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static void writeIntLengthUtf(FriendlyByteBuf buf, String value, int maxBytes) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length == 0 || bytes.length > maxBytes) throw new IllegalArgumentException("invalid UTF-8 string");
		buf.writeInt(bytes.length);
		buf.writeBytes(bytes);
	}

	private static String readIntLengthUtf(FriendlyByteBuf buf, int maxBytes) throws CharacterCodingException {
		int length = buf.readInt();
		if (length <= 0 || length > maxBytes || length > buf.readableBytes()) {
			throw new IllegalArgumentException("invalid UTF-8 length");
		}
		byte[] bytes = new byte[length];
		buf.readBytes(bytes);
		return decodeUtfStrict(bytes);
	}

	private static String decodeUtfStrict(byte[] bytes) throws CharacterCodingException {
		return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(bytes))
			.toString();
	}

	private static void writeNullableUtf(FriendlyByteBuf buf, String value, int maxBytes) {
		if (value == null) {
			buf.writeByte(0);
		} else {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			if (bytes.length == 0 || bytes.length > maxBytes) throw new IllegalArgumentException("invalid UTF-8 string");
			buf.writeByte(1);
			buf.writeInt(bytes.length);
			buf.writeBytes(bytes);
		}
	}

	private static String readNullableUtf(FriendlyByteBuf buf, int maxBytes) throws CharacterCodingException {
		int presence = buf.readUnsignedByte();
		if (presence == 0) return null;
		if (presence != 1) throw new IllegalArgumentException("invalid nullable string presence");
		return readIntLengthUtf(buf, maxBytes);
	}

	private static void writeCache(FriendlyByteBuf buf, CacheDeclaration cache) {
		buf.writeUUID(cache.serverUuid).writeUUID(cache.worldUuid);
		writeIntLengthUtf(buf, cache.coworldVersion, 32);
		writeIntLengthUtf(buf, cache.minecraftVersion, 32);
		buf.writeInt(cache.cacheFormatVersion).writeInt(cache.hashFormatVersion);
		writeNullableUtf(buf, cache.schemaId, 128);
		buf.writeInt(cache.schemaVersion);

		record SortedEntry(ManifestEntry entry, byte[] pathBytes) {
		}
		List<SortedEntry> sorted = new ArrayList<>(cache.manifest.size());
		for (ManifestEntry entry : cache.manifest) {
			sorted.add(new SortedEntry(entry, entry.path().getBytes(StandardCharsets.UTF_8)));
		}
		sorted.sort((left, right) -> {
			int root = Integer.compare(left.entry().rootKind(), right.entry().rootKind());
			return root != 0 ? root : comparedBytes(left.pathBytes(), right.pathBytes());
		});
		if (sorted.size() > MAX_MANIFEST_ENTRIES) throw new IllegalArgumentException("too many manifest entries");
		buf.writeInt(sorted.size());
		int manifestBytes = 0;
		for (SortedEntry sortedEntry : sorted) {
			ManifestEntry entry = sortedEntry.entry();
			buf.writeByte(entry.rootKind());
			byte[] path = sortedEntry.pathBytes();
			buf.writeInt(path.length);
			buf.writeBytes(path);
			buf.writeLong(entry.storedLength());
			buf.writeBytes(entry.sha256());
			manifestBytes += 1 + 4 + path.length + 8 + 32;
			if (manifestBytes > CACHE_MANIFEST_LIMIT) throw new IllegalArgumentException("manifest exceeds 32 MiB");
		}
	}

	private static CacheDeclaration readCache(FriendlyByteBuf buf) throws CharacterCodingException {
		UUID serverUuid = buf.readUUID();
		UUID worldUuid = buf.readUUID();
		String coworldVersion = readIntLengthUtf(buf, 32);
		String minecraftVersion = readIntLengthUtf(buf, 32);
		int cacheFormatVersion = buf.readInt();
		int hashFormatVersion = buf.readInt();
		String schemaId = readNullableUtf(buf, 128);
		int schemaVersion = buf.readInt();
		int count = buf.readInt();
		if (count < 0 || count > MAX_MANIFEST_ENTRIES) throw new IllegalArgumentException("invalid manifest count");
		List<ManifestEntry> manifest = new ArrayList<>(count);
		int manifestBytes = 0;
		ManifestEntry previous = null;
		for (int i = 0; i < count; i++) {
			int rootKind = buf.readUnsignedByte();
			String path = readIntLengthUtf(buf, 4096);
			validateManifestPath(path);
			long storedLength = buf.readLong();
			if (storedLength < 0) throw new IllegalArgumentException("negative stored length");
			byte[] hash = new byte[32];
			buf.readBytes(hash);
			ManifestEntry entry = new ManifestEntry(rootKind, path, storedLength, hash);
			if (previous != null) {
				int comparedRoot = Integer.compare(previous.rootKind(), entry.rootKind());
				int comparedPath = comparedBytes(previous.path(), entry.path());
				if (comparedRoot > 0 || (comparedRoot == 0 && comparedPath >= 0)) {
					throw new IllegalArgumentException("manifest entries are not canonical");
				}
			}
			previous = entry;
			manifest.add(entry);
			manifestBytes += 1 + 4 + encodedLength(path) + 8 + 32;
			if (manifestBytes > CACHE_MANIFEST_LIMIT) throw new IllegalArgumentException("manifest exceeds 32 MiB");
		}
		return new CacheDeclaration(serverUuid, worldUuid, coworldVersion, minecraftVersion,
			cacheFormatVersion, hashFormatVersion, schemaId, schemaVersion, manifest);
	}

	private static int comparedBytes(String left, String right) {
		byte[] a = left.getBytes(StandardCharsets.UTF_8);
		byte[] b = right.getBytes(StandardCharsets.UTF_8);
		return comparedBytes(a, b);
	}

	private static int comparedBytes(byte[] a, byte[] b) {
		int limit = Math.min(a.length, b.length);
		for (int i = 0; i < limit; i++) {
			int cmp = Integer.compare(a[i] & 0xff, b[i] & 0xff);
			if (cmp != 0) return cmp;
		}
		return Integer.compare(a.length, b.length);
	}
}

package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkSetType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C — 세트 효과의 <b>지금 상태</b>와, 툴팁에 「아직 안 가진 것」을 띄우는 데 필요한 목록.
 *
 * <p>클라이언트는 증강 풀을 아예 읽지 않는다 — {@code PerkRegistry.load} 를 부르는 자리는
 * {@code SharedFateMod} 한 곳뿐이고 그것은 서버 경로다. 보유 증강조차 id 없이
 * {@code (이름, 설명, 등급)} 문자열로만 받는다({@link PerkSyncPayload}). 그래서 「채굴을 몇 개
 * 가졌는가」도, 「채굴에 무엇이 더 있는가」도 서버가 내려보내야만 화면에 뜬다.
 *
 * <h2>상한을 두는 이유</h2>
 * <p>{@code ByteBufCodecs.list(max)} 는 상한을 넘는 목록을 만나면 인코딩·디코딩 양쪽에서
 * 예외를 던진다. 그 예외는 패킷 한 장이 아니라 <b>접속 자체</b>를 끊는다. 그래서 상한을
 * 코덱에만 맡기지 않고 생성자에서 먼저 잘라 둔다 — 세트 줄이 상한을 넘는 날이 와도
 * 화면이 한 줄 덜 뜰 뿐 아무도 튕기지 않는다. (숫자를 적지 않는다. 유형이 열하나이던 때
 * 「열두 개가 되는 날」로 적어 두었더니 그날이 먼저 와 이 줄만 낡았다.)
 *
 * @param sets    유형별 진행 상황. 유형마다 최대 한 줄이다
 * @param catalog 유형에 속한 증강의 이름표. 툴팁이 「아직 없는 것」을 고르는 데 쓴다
 */
public record PerkSetSyncPayload(List<SetLine> sets, List<TierLine> tiers,
		List<CatalogEntry> catalog)
		implements CustomPacketPayload {

	/**
	 * 실을 수 있는 세트 줄의 개수 상한.
	 *
	 * <p>유형 개수와 같다. 숫자를 따로 적지 않고 {@link PerkSetType} 에서 세는 이유는, 유형이
	 * 늘었을 때 여기를 함께 고치는 것을 잊으면 <b>맨 뒤 유형이 조용히 사라지기</b> 때문이다.
	 */
	public static final int MAX_SETS = PerkSetType.values().length;

	/**
	 * 실을 수 있는 이름표의 개수 상한.
	 *
	 * <p>증강 하나가 유형을 여럿 가질 수 있어 이름표 수는 증강 수보다 많아진다. 지금 유형이
	 * 붙은 증강이 85개이고 이름표는 90개라, 여유가 두 배에서 1.7배로 줄었다 — <b>증강을 더
	 * 넣을 때 여기를 함께 볼 것.</b> 넘으면 툴팁의 뒷부분이 조용히 빈다.
	 *
	 * <p>넘칠 때는 <b>뒤에서부터</b> 버린다. 그래서 서버는 이 목록을 <b>유형별로 모아서</b>
	 * 실어야 한다 — 유형이 뒤섞여 있으면 잘린 뒤에 여러 유형의 툴팁이 조금씩 함께 빈다.
	 */
	public static final int MAX_CATALOG = 160;

	/**
	 * 실을 수 있는 단계 줄의 개수 상한.
	 *
	 * <p>지금 28 단계인데 넉넉히 잡았다. 단계는 유형마다 하나에서 셋이고, 유형이 열넷이라
	 * 유형마다 넷씩 가져도 56 이라 이 값을 넘지 못한다.
	 *
	 * <p>넘칠 때는 <b>뒤에서부터</b> 버린다. {@link #MAX_CATALOG} 와 같은 이유로 서버는 이
	 * 목록도 <b>유형별로 모아서</b> 실어야 한다.
	 */
	public static final int MAX_TIERS = 64;

	/** 세트가 하나도 없는 상태. 팀이 없거나 증강을 쓰지 않는 팀에 보낸다. */
	public static final PerkSetSyncPayload EMPTY =
			new PerkSetSyncPayload(List.of(), List.of(), List.of());

	public static final Type<PerkSetSyncPayload> TYPE = new Type<>(SharedFateMod.id("perk_sets"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PerkSetSyncPayload> CODEC =
			StreamCodec.composite(
					SetLine.CODEC.apply(ByteBufCodecs.list(MAX_SETS)), PerkSetSyncPayload::sets,
					TierLine.CODEC.apply(ByteBufCodecs.list(MAX_TIERS)), PerkSetSyncPayload::tiers,
					CatalogEntry.CODEC.apply(ByteBufCodecs.list(MAX_CATALOG)),
					PerkSetSyncPayload::catalog,
					PerkSetSyncPayload::new);

	public PerkSetSyncPayload {
		sets = trim(sets, MAX_SETS);
		tiers = trim(tiers, MAX_TIERS);
		catalog = trim(catalog, MAX_CATALOG);
	}

	/**
	 * 단계 설명 없이 만든다. <b>「단계를 안 보낸다」는 뜻이지 「단계가 없다」가 아니다.</b>
	 *
	 * <p>실제 송신 경로({@code PerkSetBroadcaster})는 언제나 세 인자짜리를 쓴다. 이걸로 보낸 값을
	 * 화면이 받으면 툴팁에 단계 줄이 한 줄도 안 뜬다.
	 */
	public PerkSetSyncPayload(List<SetLine> sets, List<CatalogEntry> catalog) {
		this(sets, List.of(), catalog);
	}

	/**
	 * 세트 단계 하나. 툴팁이 「2: 광물에서 나오는 경험치가 50% 늘어납니다」로 그린다.
	 *
	 * <p><b>설명을 서버가 보낸다.</b> 클라이언트는 세트 정의 파일을 읽지 않으므로
	 * ({@code PerkSetRegistry} 는 서버 전용) 스스로는 단계가 무엇을 하는지 알 방법이 없다.
	 * 설정 파일에서 값을 고친 서버에 접속해도 툴팁이 그 서버의 설명을 그대로 보여 준다.
	 *
	 * @param typeId      {@link PerkSetType#id()}. {@link SetLine#typeId()} 와 맞물린다
	 * @param count       이 단계가 열리는 데 필요한 개수
	 * @param description 무엇을 하는 단계인가
	 * @param active      지금 켜져 있는가
	 */
	public record TierLine(String typeId, int count, String description, boolean active) {

		public TierLine {
			typeId = text(typeId);
			description = text(description);
			count = Math.max(0, count);
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, TierLine> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, TierLine::typeId,
						ByteBufCodecs.VAR_INT, TierLine::count,
						ByteBufCodecs.STRING_UTF8, TierLine::description,
						ByteBufCodecs.BOOL, TierLine::active,
						TierLine::new);
	}

	/**
	 * 유형 하나의 진행 상황.
	 *
	 * <p>「채굴 2/3」은 {@code owned=2, nextThreshold=3} 이다. 임계값을 클라이언트가 다시 세지
	 * 않는 이유는 {@link PerkSetType#threshold()} 가 시뮬레이션 결과로 자주 바뀌는 값이라,
	 * 양쪽에 적어 두면 <b>버전이 다른 클라이언트가 다른 숫자를 그린다</b>는 것이다.
	 *
	 * @param typeId        {@link PerkSetType#id()}. 예: {@code mining}. 이름표를 고르는 열쇠다
	 * @param displayName   화면에 적을 한국어 이름. 예: {@code 채굴}
	 * @param owned         지금 가진 그 유형 증강의 개수
	 * @param nextThreshold 다음 단계에 필요한 개수. 더 오를 곳이 없으면 0
	 * @param activeTier    지금 켜져 있는 단계. 하나도 안 켜졌으면 0
	 * @param intervalTicks 이 유형이 되풀이하는 일의 주기(틱). 지금은 「보급」만 0 보다 크다.
	 *                      화면이 「다음 보급까지 04:12」를 <b>스스로</b> 세는 데 쓴다. 남은 시간을
	 *                      싣지 않는 까닭은 {@code com.sharedfate.ui.SupplyCountdown} 머리
	 *                      주석에 있다 — 이 값은 단계가 바뀔 때만 달라지므로 「달라졌을 때만
	 *                      보낸다」가 그대로 살아 있다
	 * @param anchorTick    그 되풀이가 켜진 오버월드 게임 시간. 경계는 이 자리부터 주기마다다.
	 *                      0 이면 게임 시간의 배수를 경계로 삼는다(이 칸을 모르는 옛 규칙)
	 */
	public record SetLine(String typeId, String displayName, int owned, int nextThreshold,
			int activeTier, int intervalTicks, long anchorTick) {

		/** 켜진 시점을 모르는 자리에서 쓰는 짧은 생성자. */
		public SetLine(String typeId, String displayName, int owned, int nextThreshold,
				int activeTier, int intervalTicks) {
			this(typeId, displayName, owned, nextThreshold, activeTier, intervalTicks, 0L);
		}

		/** 주기가 없는 유형을 짧게 적는 생성자. */
		public SetLine(String typeId, String displayName, int owned, int nextThreshold,
				int activeTier) {
			this(typeId, displayName, owned, nextThreshold, activeTier, 0, 0L);
		}

		public SetLine {
			typeId = text(typeId);
			displayName = text(displayName);
			// VAR_INT 는 음수를 싣지 못한다. 판정이 어긋나도 접속이 끊기면 안 된다.
			owned = Math.max(0, owned);
			nextThreshold = Math.max(0, nextThreshold);
			activeTier = Math.max(0, activeTier);
			intervalTicks = Math.max(0, intervalTicks);
			// VAR_LONG 도 음수를 싣지 못한다. 0 이 「모른다」다.
			anchorTick = Math.max(0L, anchorTick);
		}

		/** 세트 효과가 이미 켜져 있는가. */
		public boolean active() {
			return activeTier > 0;
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, SetLine> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, SetLine::typeId,
						ByteBufCodecs.STRING_UTF8, SetLine::displayName,
						ByteBufCodecs.VAR_INT, SetLine::owned,
						ByteBufCodecs.VAR_INT, SetLine::nextThreshold,
						ByteBufCodecs.VAR_INT, SetLine::activeTier,
						ByteBufCodecs.VAR_INT, SetLine::intervalTicks,
						ByteBufCodecs.VAR_LONG, SetLine::anchorTick,
						SetLine::new);
	}

	/**
	 * 유형에 속한 증강 하나의 이름표.
	 *
	 * <p>가진 것까지 함께 싣는다. 툴팁이 쓰는 것은 {@code owned == false} 인 것뿐이지만,
	 * <b>가진 것을 빼고 보내면 「전부 모았다」와 「목록이 잘렸다」를 구별할 수 없다.</b>
	 *
	 * @param typeId   {@link PerkSetType#id()}. {@link SetLine#typeId()} 와 맞물린다
	 * @param perkName 증강 이름
	 * @param rarity   등급 문자열({@code silver} / {@code gold} / {@code prism}). 글자색이 된다
	 * @param owned    이미 가진 증강인가
	 */
	public record CatalogEntry(String typeId, String perkName, String rarity, boolean owned) {

		public CatalogEntry {
			typeId = text(typeId);
			perkName = text(perkName);
			rarity = text(rarity);
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, CatalogEntry> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, CatalogEntry::typeId,
						ByteBufCodecs.STRING_UTF8, CatalogEntry::perkName,
						ByteBufCodecs.STRING_UTF8, CatalogEntry::rarity,
						ByteBufCodecs.BOOL, CatalogEntry::owned,
						CatalogEntry::new);
	}

	/** 목록을 상한까지만 남기고 자른다. 언제나 바꿀 수 없는 목록을 돌려준다. */
	private static <T> List<T> trim(List<T> values, int limit) {
		if (values == null) {
			return List.of();
		}
		if (values.size() <= limit) {
			return List.copyOf(values);
		}
		return List.copyOf(new ArrayList<>(values.subList(0, limit)));
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

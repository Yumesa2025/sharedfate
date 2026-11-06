package com.sharedfate.ui;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkRarity;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 등급마다 화면에 쓰는 색과 그림 — 증강 선택창 · 보유 목록 · 팀 화면 · 발동 알림이 같은 값을 쓰도록 한
 * 곳에 둔다. 넷이 각자 적으면 언젠가 한 곳만 다른 금색이 된다({@link DragonPrepStyle} 과 같은 까닭).
 *
 * <p>그림은 {@code assets/sharedfate/textures/gui/sprites/perk/} 에 있다. 바닐라 {@code gui} 아틀라스가
 * 모든 이름공간의 {@code textures/gui/sprites} 를 읽으므로 아틀라스 설정 없이 {@code sharedfate:perk/...}
 * 로 부를 수 있다. 늘이는 방식(나인 슬라이스 테두리 폭)은 그림 옆의 {@code .png.mcmeta} 가 정한다.
 *
 * <p>클라이언트 코드가 아니라 공용 쪽에 둔 것은 발동 알림 줄({@link PerkProcFeed})이 공용 쪽에 있어서다.
 * 그림 이름은 {@link Identifier} 일 뿐이라 서버에서 읽혀도 아무것도 불러오지 않는다.
 */
public enum PerkRarityStyle {
	SILVER(PerkRarity.SILVER, 0xFFC0C6CC),
	GOLD(PerkRarity.GOLD, 0xFFFFC63A),
	PRISM(PerkRarity.PRISM, 0xFF5FE0D8),
	/** 「드래곤전 대비」 — 등급이 아닌 분류. 엔더 연보라. */
	TRIAL(PerkRarity.TRIAL, DragonPrepStyle.COLOR);

	/** 카드 둘레의 빛. 흰색이라 등급색으로 물들여 그린다. 나인 슬라이스 테두리 12. */
	public static final Identifier CARD_GLOW = sprite("card_glow");
	/** 「드래곤전 대비」 투표 카드의 뒷면 틀. 나인 슬라이스 테두리 8. */
	public static final Identifier CARD_BACK = sprite("card_back");
	/** 뒷면 한가운데의 문양. 32×32 그대로 그린다. */
	public static final Identifier CARD_BACK_EMBLEM = sprite("card_back_emblem");
	/** 판 바탕(세트 판 · 보유 증강 · 팀 화면). 나인 슬라이스 테두리 8. */
	public static final Identifier PANEL = sprite("panel");
	/** 「다음 증강까지」 게이지 — 평소의 금색. 182×5. */
	public static final Identifier GAUGE_GOLD_BG = sprite("gauge_gold_bg");
	public static final Identifier GAUGE_GOLD_FILL = sprite("gauge_gold_fill");
	/** 「드래곤전 대비 증강까지」 게이지 — 엔더 연보라. 182×5. */
	public static final Identifier GAUGE_TRIAL_BG = sprite("gauge_trial_bg");
	public static final Identifier GAUGE_TRIAL_FILL = sprite("gauge_trial_fill");

	/** 등급에 매이지 않는 그림 전부. 시험이 파일이 있는지 하나씩 본다. */
	public static final List<Identifier> SHARED_SPRITES = List.of(CARD_GLOW, CARD_BACK,
			CARD_BACK_EMBLEM, PANEL, GAUGE_GOLD_BG, GAUGE_GOLD_FILL, GAUGE_TRIAL_BG, GAUGE_TRIAL_FILL);

	/** 빛 그림을 카드보다 사방으로 이만큼 크게 그린다. */
	public static final int GLOW_SPREAD = 8;
	/** 등급 띠 그림의 원래 높이. */
	public static final int BAND_HEIGHT = 11;
	/** 등급 문양의 한 변. */
	public static final int EMBLEM_SIZE = 11;
	/** 뒷면 문양의 한 변. */
	public static final int BACK_EMBLEM_SIZE = 32;

	private final PerkRarity rarity;
	private final int color;
	private final Identifier card;
	private final Identifier band;
	private final Identifier emblem;

	PerkRarityStyle(PerkRarity rarity, int color) {
		this.rarity = rarity;
		this.color = color;
		String suffix = rarity.id();
		this.card = sprite("card_" + suffix);
		this.band = sprite("band_" + suffix);
		this.emblem = sprite("emblem_" + suffix);
	}

	/** 등급에 맞는 모양. 모르면(null) 실버로 본다 — 등급 하나 때문에 화면이 비면 안 된다. */
	public static PerkRarityStyle of(PerkRarity rarity) {
		if (rarity == null) {
			return SILVER;
		}
		return switch (rarity) {
			case SILVER -> SILVER;
			case GOLD -> GOLD;
			case PRISM -> PRISM;
			case TRIAL -> TRIAL;
		};
	}

	/**
	 * 서버가 보낸 등급 문자열에 맞는 모양. {@code gold · prism · trial} 이 아니면 전부 실버다.
	 *
	 * <p>옛 이름(rare · epic …)을 받아 주는 {@link PerkRarity#fromId} 를 쓰지 않는다 — 보유 목록 ·
	 * 팀 화면 · 발동 알림이 예전부터 이 넷만 갈라 왔고, 그 동작을 그대로 둔다.
	 */
	public static PerkRarityStyle ofId(String rarityId) {
		if (rarityId == null) {
			return SILVER;
		}
		return switch (rarityId) {
			case "gold" -> GOLD;
			case "prism" -> PRISM;
			case "trial" -> TRIAL;
			default -> SILVER;
		};
	}

	/** {@link #ofId} 의 색만. 글자색으로 바로 쓰는 자리가 많다. */
	public static int colorOf(String rarityId) {
		return ofId(rarityId).color();
	}

	public PerkRarity rarity() {
		return rarity;
	}

	/** 등급색. 띠 · 글자 · 빛이 함께 쓴다. */
	public int color() {
		return color;
	}

	/** 카드 틀. 48×48, 나인 슬라이스 테두리 8. 가운데는 어둡다. */
	public Identifier card() {
		return card;
	}

	/** 카드 맨 위의 등급 띠. 116×11, 좌우 4만 고정이다. 가운데가 밝아 글자는 어둡게 쓴다. */
	public Identifier band() {
		return band;
	}

	/** 등급 띠 왼쪽 끝의 문양. 11×11. */
	public Identifier emblem() {
		return emblem;
	}

	/** 등급에 매인 그림 셋. */
	public List<Identifier> sprites() {
		return List.of(card, band, emblem);
	}

	private static Identifier sprite(String name) {
		return Identifier.fromNamespaceAndPath(SharedFateMod.MOD_ID, "perk/" + name);
	}
}

package com.sharedfate.perk.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 정해진 구조물들의 <b>좌표만</b> 알려 주는 효과. 증강 「유적 감별사」가 이 타입 하나다.
 *
 * <pre>{@code
 * { "type": "ruin_survey", "dimension": "minecraft:overworld", "search_radius": 100,
 *   "structures": [
 *     { "id": "minecraft:ancient_city", "label": "고대 도시" },
 *     { "id": "minecraft:mansion",      "label": "숲의 저택" } ] }
 * }</pre>
 *
 * <p>지도도 나침반도 주지 않는다. 좌표 한 줄씩이다.
 *
 * <h2>{@code compass_target} 과 무엇이 다른가</h2>
 * <p>{@link CompassTargetEffect} 는 <b>하나</b>를 찾아 <b>나침반 바늘</b>에 꽂는다. 이쪽은
 * <b>여럿</b>을 찾아 <b>글자</b>로 보여 준다. 나침반은 팀에 하나뿐이라 동시에 두 곳을 가리킬
 * 수 없는데, 좌표는 몇 줄이든 나란히 놓을 수 있다.
 *
 * <h2>{@code label} 을 왜 정의에 적는가</h2>
 * <p>구조물 이름({@code minecraft:mansion})을 사람이 읽는 말로 옮기는 표를 코드에 두면, 구조물
 * 하나를 더할 때마다 정의와 코드를 함께 고쳐야 한다. 정의 한 곳에서 끝나야 한다.
 *
 * <p>구조물 <b>태그</b>는 받지 않는다. 태그는 여러 구조물의 묶음이라 어느 것을 찾았는지에 따라
 * 이름이 달라지는데, 그러면 {@code label} 이 거짓말을 한다.
 *
 * <h2>이 클래스는 아무것도 찾지 않는다</h2>
 * <p>「무엇을 어디서 얼마나 넓게 찾을 것인가」만 든다. 실제 탐색과 기억은
 * {@link com.sharedfate.perk.PerkRuinSurvey} 가 맡는다. 구조물 이름을 레지스트리에서 푸는 일도
 * 서버가 떠 있어야 하므로 그때 미룬다 — {@link CompassTargetEffect} 와 같은 이유·같은 모양이다.
 */
public final class RuinSurveyEffect implements PerkEffect {
	/** 적지 않았을 때 쓰는 탐색 반경(청크). {@code compass_target} 과 같은 값으로 맞춘다. */
	public static final int DEFAULT_SEARCH_RADIUS = CompassTargetEffect.DEFAULT_SEARCH_RADIUS;

	/** 탐색 반경 상한(청크). */
	public static final int MAX_SEARCH_RADIUS = CompassTargetEffect.MAX_SEARCH_RADIUS;

	/**
	 * 한 정의에 적을 수 있는 구조물 수 상한.
	 *
	 * <p>좌표 한 줄이 곧 한 번의 구조물 탐색이다. 탐색은 서버 스레드를 붙잡으므로, 정의 실수로
	 * 열 개가 들어오면 고른 그 순간 서버가 눈에 띄게 멈춘다.
	 */
	public static final int MAX_STRUCTURES = 4;

	/** 이름표 길이 상한. 이보다 길면 좌표 줄이 화면을 넘는다. */
	public static final int MAX_LABEL_LENGTH = 16;

	/**
	 * 찾을 구조물 하나.
	 *
	 * @param key   구조물 이름
	 * @param label 사람에게 보여 줄 말
	 */
	public record Target(ResourceKey<Structure> key, String label) {
		/**
		 * 이 구조물을 레지스트리에서 푼다. 없으면 {@code null}.
		 *
		 * <p>{@code ChunkGenerator.findNearestMapStructure} 가 {@code HolderSet} 을 받으므로
		 * 홀더 하나짜리 집합으로 감싼다. {@link CompassTargetEffect#resolve} 와 같은 모양이다.
		 */
		public @Nullable HolderSet<Structure> resolve(@Nullable RegistryAccess registries) {
			if (registries == null) {
				return null;
			}
			Holder.Reference<Structure> holder = registries.get(key).orElse(null);
			return holder == null ? null : HolderSet.direct(List.of(holder));
		}
	}

	private final ResourceKey<Level> dimension;
	private final int searchRadius;
	private final List<Target> targets;

	public RuinSurveyEffect(ResourceKey<Level> dimension, int searchRadius, List<Target> targets) {
		this.dimension = dimension;
		this.searchRadius = searchRadius;
		this.targets = List.copyOf(targets);
	}

	/** JSON 에서 만든다. 정의가 잘못됐으면 경고를 남기고 {@code null}. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		String rawDimension = PerkEffectType.readString(json, "dimension");
		Identifier dimensionId =
				rawDimension == null ? null : Identifier.tryParse(rawDimension.trim());
		if (dimensionId == null) {
			SharedFateMod.LOGGER.warn("증강 {}: ruin_survey 의 dimension 이 없거나 잘못됐습니다 ({})",
					perkId, rawDimension);
			return null;
		}

		int radius = PerkEffectType.readInt(json, "search_radius", DEFAULT_SEARCH_RADIUS);
		if (radius < 1 || radius > MAX_SEARCH_RADIUS) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: ruin_survey 의 search_radius 가 1~{} 범위를 벗어났습니다 ({})",
					perkId, MAX_SEARCH_RADIUS, radius);
			return null;
		}

		JsonElement element = json.get("structures");
		if (element == null || !element.isJsonArray()) {
			SharedFateMod.LOGGER.warn("증강 {}: ruin_survey 에 structures 배열이 없습니다", perkId);
			return null;
		}
		JsonArray array = element.getAsJsonArray();
		if (array.isEmpty() || array.size() > MAX_STRUCTURES) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: ruin_survey 의 structures 가 비었거나 {}개를 넘습니다 ({}개)",
					perkId, MAX_STRUCTURES, array.size());
			return null;
		}

		List<Target> targets = new ArrayList<>(array.size());
		for (JsonElement raw : array) {
			if (raw == null || !raw.isJsonObject()) {
				SharedFateMod.LOGGER.warn("증강 {}: ruin_survey 의 structures 항목이 객체가 아닙니다",
						perkId);
				return null;
			}
			JsonObject entry = raw.getAsJsonObject();
			String rawId = PerkEffectType.readString(entry, "id");
			// 태그(#)는 받지 않는다. 어느 것을 찾았는지에 따라 label 이 거짓말을 하게 된다.
			if (rawId == null || rawId.isBlank() || rawId.trim().startsWith("#")) {
				SharedFateMod.LOGGER.warn(
						"증강 {}: ruin_survey 의 구조물 id 가 없거나 태그입니다 ({}). "
								+ "구조물 이름 하나만 적을 수 있습니다",
						perkId, rawId);
				return null;
			}
			Identifier structureId = Identifier.tryParse(rawId.trim());
			if (structureId == null) {
				SharedFateMod.LOGGER.warn("증강 {}: 올바르지 않은 구조물 이름 {}", perkId, rawId);
				return null;
			}
			String label = PerkEffectType.readString(entry, "label");
			if (label == null || label.isBlank() || label.trim().length() > MAX_LABEL_LENGTH) {
				SharedFateMod.LOGGER.warn(
						"증강 {}: ruin_survey 의 label 이 없거나 {}자를 넘습니다 ({})",
						perkId, MAX_LABEL_LENGTH, label);
				return null;
			}
			targets.add(new Target(ResourceKey.create(Registries.STRUCTURE, structureId),
					label.trim()));
		}

		return new RuinSurveyEffect(ResourceKey.create(Registries.DIMENSION, dimensionId),
				radius, targets);
	}

	/** 구조물을 찾을 차원. */
	public ResourceKey<Level> dimension() {
		return dimension;
	}

	/** 탐색 반경(청크). */
	public int searchRadius() {
		return searchRadius;
	}

	/** 찾을 구조물들. 정의에 적힌 순서 그대로이며, 좌표 줄의 순서도 이것이다. */
	public List<Target> targets() {
		return targets;
	}
}

package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/**
 * 최후의 저항의 <b>고정 피해</b>. 적힌 값이 그대로 팀 체력에서 빠진다.
 *
 * <p>사람이 정한 것(2026-11-07) — 「받는 피해 감소에 방어 엄청 늘리니까 엄청 쉽게 깼어. 그래서 고정딜
 * 시스템을 넣는 게 나을 거 같아서」. 공허 흡입(불 결계 · 터짐) · 십자 균열은 이것으로 바꾸고, 상시 번개는
 * 원래 피해 위에 이것을 조금 얹는다.
 *
 * <h2>무엇을 지나가는가</h2>
 *
 * <p>피해 종류 {@code sharedfate:dragon_true_damage}(데이터 {@code damage_type/dragon_true_damage.json})에
 * 바닐라 태그를 달아 둔다.
 *
 * <ul>
 *   <li>{@code bypasses_armor} — 방어도 · 방어 강도</li>
 *   <li>{@code bypasses_enchantments} — 보호 계열 마법부여</li>
 *   <li>{@code bypasses_effects} · {@code bypasses_resistance} — 저항</li>
 *   <li>{@code bypasses_shield} — 방패</li>
 *   <li>{@code bypasses_cooldown} — 피격 쿨타임. 상시 번개 바로 뒤에 얹는 고정 몫이 「직전보다 약한 한 대」로
 *       버려지지 않게 하고, 불 결계 다음 틱의 터짐이 차액만 들어가지 않게 한다.</li>
 *   <li>{@code no_knockback}</li>
 * </ul>
 *
 * <p>{@code scaling} 은 {@code never} 라 난이도 곱도 없다. 이 저장소의 증강 배율(받는 피해 감소 · 방어 세트 ·
 * 특정 피해 감소)과 「난이도 상승」은 {@code LivingEntityPerkDamageMixin} 이 {@link #is} 를 보고 건너뛴다.
 *
 * <p>남는 것 — <b>흡수 하트</b>(바닐라가 피해 종류와 상관없이 먼저 깎는다) · 불사의 토템 · 「완충」의 나눠 받기 ·
 * 드래곤전 대비 증강 「바닥 감각」(부르는 쪽이 곱한다).
 *
 * <p>사망 문구는 바닐라 {@code dragonBreath}(「드래곤의 숨결」)를 빌린다. 이 모드에는 언어 파일이 없어 새 문구를
 * 만들면 클라이언트에 키가 그대로 보인다.
 */
public final class DragonTrueDamage {
	public static final ResourceKey<DamageType> TYPE =
			ResourceKey.create(Registries.DAMAGE_TYPE, SharedFateMod.id("dragon_true_damage"));

	private DragonTrueDamage() {
	}

	private static volatile boolean warned;

	/**
	 * 실체 없는 고정 피해원. 실체가 붙으면 바닐라가 스스로 밀어낸다(패턴 파일의 「지키는 것」).
	 *
	 * <p>데이터가 안 읽혔으면(다른 데이터팩이 지웠다든가) 패턴 틱 한가운데서 예외로 서버를 세우지 않고, 경고를
	 * 한 번 남긴 뒤 바닐라 {@code magic()} 으로 넣는다 — 방어도는 지나가지만 고정은 아니다.
	 */
	public static DamageSource source(ServerLevel level) {
		var type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).get(TYPE);
		if (type.isPresent()) {
			return new DamageSource(type.get());
		}
		if (!warned) {
			warned = true;
			SharedFateMod.LOGGER.error("피해 종류 {} 를 찾지 못해 최후의 저항 고정 피해를 마법 피해로 넣습니다.",
					TYPE.identifier());
		}
		return level.damageSources().magic();
	}

	public static boolean is(DamageSource source) {
		return source.is(TYPE);
	}
}

package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import java.util.function.BooleanSupplier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

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
 * </ul>
 *
 * <p><b>밀침은 남긴다.</b> 0.45.9-dev 는 {@code no_knockback} 도 달아, 예전 번개 · 폭발 피해가 주던 밀침(출처 쪽,
 * 출처가 없으면 무작위 방향으로 0.4 · 그 순간 속도 절반)이 사라졌다. 그래서 흡입 원 안의 불 결계가 매초 주던 감속이
 * 없어져 빠져나오기 쉬워졌다. 사람이 정한 것(2026-11-10) — 「맞아도 밀침 있게, 고정 피해에도」. 태그 파일은 빈 채로
 * 남겼다. 상시 번개는 번개 한 대 위에 고정 피해를 얹으므로 같은 틱에 두 번 밀린다.
 *
 * <h2>피격 쿨타임은 태그가 아니라 {@link #hurt} 로 지나간다</h2>
 *
 * <p>⚠ 0.45.9-dev 는 {@code bypasses_cooldown} 태그도 달았다. 26.3 {@code LivingEntity.hurtServer} 는 그 태그의
 * 피해를 「새로 맞음」 갈래로 받아 {@code lastHurt = amount} · {@code damageCooldownTime = 20} 으로 <b>덮어쓴다.</b>
 * 그래서 작은 고정 피해(0.5 ~ 2)가 쿨타임의 기준을 끌어내렸다 — 상시 번개(24.5) 바로 뒤의 고정 2 가 기준을 2 로
 * 낮춰, 0.5초 동안 막혀야 할 드래곤 몸통 · 브레스가 거의 다 들어왔고, 흡입 원 안에서는 불 결계(0.5)가 매초 기준을
 * 0.5 로 되돌렸다(2026-11-10 검토에서 확정).
 *
 * <p>지금은 태그를 뺐고(태그 파일은 빈 채로 남겼다), {@link #hurt} 가 넣기 직전에 쿨타임을 0 으로 내려 「새로
 * 맞음」 갈래로 넣은 뒤 <b>{@code lastHurt} 와 {@code damageCooldownTime} 을 넣기 전 값으로 되돌린다.</b> 고정
 * 피해는 쿨타임과 상관없이 매번 온전히 들어가고, 다른 피해의 쿨타임 판정에는 흔적을 남기지 않는다. 무기 3
 * 「한 번 더」({@code PerkFollowUpStrike.strike})와 같은 모양이다.
 *
 * <p>「완충」은 바닐라 쿨타임을 따로 흉내 낸다({@code SpreadDamageManager.capture} 의 {@code Guard}). 그쪽도
 * {@link #applying} 을 보고 고정 피해를 통째로 받아들이며 흉내 낸 쿨타임은 건드리지 않는다 — 같은 규칙이다.
 * 미뤄 둔 몫을 넣는 {@code SpreadDamageManager.deliver} 는 원래부터 두 값을 되돌린다.
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

	/** {@link #hurt} 가 고정 피해를 넣는 중인가. 서버 스레드에서만 만진다. */
	private static boolean applying;

	/**
	 * 지금 고정 피해를 넣는 중인가 — 피격 쿨타임을 흉내 내는 쪽(「완충」)이 「쿨타임과 무관」을 맞추려고 묻는다.
	 * {@link #hurt} 의 {@code hurtServer} 호출 동안만 참이다.
	 */
	public static boolean applying() {
		return applying;
	}

	/**
	 * 고정 피해를 넣는다. <b>최후의 저항이 고정 피해를 넣는 자리는 모두 이것을 지난다</b>
	 * ({@code DragonTrueDamageTest} 가 바이트코드로 붙든다).
	 *
	 * <p>피격 쿨타임과 상관없이 매번 온전히 들어가고, 넣은 뒤 대상의 {@code lastHurt} · {@code damageCooldownTime}
	 * 을 넣기 전 값으로 되돌린다. 까닭은 머리말 「피격 쿨타임은 태그가 아니라」.
	 *
	 * @return {@code hurtServer} 가 돌려준 값
	 */
	public static boolean hurt(ServerLevel level, LivingEntity target, float amount) {
		DamageSource source = source(level);
		return bypassingCooldown(target, () -> target.hurtServer(level, source, amount));
	}

	/**
	 * 쿨타임을 0 으로 내리고 {@code hit} 를 부른 뒤 두 값을 되돌린다. {@code hit} 가 터져도 되돌린다.
	 *
	 * <p>살아 있는 월드 없이 시험하려고 실제 {@code hurtServer} 호출을 떼어 두었다.
	 *
	 * <p>죽었어도 되돌린다 — 「한 번 더」는 산 몹에만 되돌리지만, 여기 대상은 플레이어라 되살아나면 새 개체가
	 * 되고 죽은 개체의 두 칸은 아무도 읽지 않는다. 불사의 토템으로 버틴 경우에는 되돌려야 하므로 가리지 않는다.
	 */
	static boolean bypassingCooldown(LivingEntity target, BooleanSupplier hit) {
		SpreadSliceAccess access = (SpreadSliceAccess) (Object) target;
		int cooldown = target.damageCooldownTime;
		float lastHurt = access.sharedfate$lastHurt();
		boolean outer = applying;
		target.damageCooldownTime = 0;
		applying = true;
		try {
			return hit.getAsBoolean();
		} finally {
			applying = outer;
			target.damageCooldownTime = cooldown;
			access.sharedfate$setLastHurt(lastHurt);
		}
	}
}

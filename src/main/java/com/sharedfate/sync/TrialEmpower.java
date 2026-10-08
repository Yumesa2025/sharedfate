package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * <b>화면 없이 걸리는 자리</b>의 유일한 알림 — 「드래곤이 세졌다」.
 *
 * <h2>왜 생겼는가</h2>
 *
 * <p>사람이 플레이해 보고 <b>「80프로 달성할떄 달성햇다고 나오는 이펙트나 연출이 있으면좋겟어.
 * 80프로떄도 착지강화라고 카드가 안나오고 그냥 안띄워도되니 화면에 강화만 시켜주고」</b>라고
 * 정했다. 그래서 체력 80% 자리가 {@code Reveal.FIXED_SCREEN} 에서
 * {@link TrialCatalog.Reveal#SILENT} 로 바뀌었고, 카드 화면이 사라진 자리를 이것이 메운다.
 *
 * <h2>⚠ 대신 잃은 것 — 「무엇이 걸렸는지」를 글로 알 길이 없다</h2>
 *
 * <p>이 연출은 <b>「세졌다」까지만</b> 말한다. 「착지 충격이 걸렸다」는 말하지 않는다. 그래서
 * 다음에 드래곤이 착지할 때 바닥이 갑자기 터지는데 <b>사람은 이유를 모른다.</b> 카드 화면이
 * 하던 일이 정확히 그것이었다({@code TrialCatalog.Reveal.FIXED_SCREEN} 의 「예고 없이 걸리면
 * 무엇에 맞았는지 알 길이 없다」).
 *
 * <p><b>사람이 그 대가를 알고 고른 것이다.</b> 되돌리지 말 것 — 되돌리고 싶으면 자리의
 * {@code Reveal} 을 바꾸는 한 줄이고, 그 판단은 사람의 것이다.
 *
 * <h2>한 틱에 끝난다 — 상태를 들지 않는다</h2>
 *
 * <p>여러 틱에 걸쳐 크레셴도를 만들면 그것을 기억할 자리가 필요하고, 그 정적 상태는 이 저장소가
 * 실행기마다 「빠뜨리면 다음 판으로 샌다」를 반복해 적어 둔 바로 그 위험이다. 순수한 꾸밈에
 * 그 위험을 지불하지 않는다.
 *
 * <p>한 틱이어도 짧게 보이지 않는다. 세 가지가 스스로 늘어지기 때문이다.
 *
 * <ul>
 *   <li><b>먼지 파티클의 수명이 8~40틱</b>이다({@code TrialWarning.markGround} 의 수명 식).
 *       한 틱에 뿌린 고리가 0.5~2초 동안 떠 있다</li>
 *   <li><b>드래곤 울음이 몇 초짜리 소리</b>다</li>
 *   <li><b>화면 흔들림이 10틱에 걸쳐 잦아든다</b>({@code TrialWarning.shakeEach})</li>
 * </ul>
 *
 * <h2>자막도 타이틀도 쓰지 않는다</h2>
 *
 * <p>자막은 이 저장소에서 전부 걷어냈다({@code TrialWarning.shout}). 화면 가운데 큰 글자는
 * 입장 연출의 「엔더 드래곤 시련 전투」 하나에만 쓴다 — 거기에 이 자리까지 얹으면 타이틀이
 * 「전투가 시작됐다」라는 뜻을 잃는다. 게다가 사람이 <b>「그냥 안띄워도되니」</b>라고 했다.
 */
public final class TrialEmpower {

	/** 드래곤을 두르는 고리의 점 수. */
	private static final int DRAGON_RING_POINTS = 40;
	/** 그 고리의 반경(블록). 드래곤 몸(가로 16칸)보다 커야 고리로 읽힌다. */
	private static final double DRAGON_RING_RADIUS = 10.0;
	/** 발밑 고리의 점 수. 사람마다 하나씩이다. */
	private static final int FOOT_RING_POINTS = 24;
	/** 발밑 고리의 반경(블록). */
	private static final double FOOT_RING_RADIUS = 2.5;
	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다({@code TrialWarning} 과 같은 이유). */
	private static final double GROUND_OFFSET = 0.15;

	/**
	 * 한 틱에 나가는 점 수의 상한.
	 *
	 * <p>드래곤 고리 하나 + 사람 수만큼의 발밑 고리다. 팀이 많아야 넷이라
	 * {@code 40 + 4 × 24 = 136} 이고, 예산 400~440({@code TrialEnderPulse.MAX_POINTS_PER_TICK}
	 * 설명) 안이다. <b>팀 인원이 늘면 이 셈도 늘어난다</b> — 발밑 고리를 키우려는 사람은 여기를
	 * 먼저 볼 것.
	 */
	static final int WORST_TICK_POINTS = DRAGON_RING_POINTS + 4 * FOOT_RING_POINTS;

	private TrialEmpower() {
	}

	/**
	 * 「세졌다」를 한 틱에 내보낸다.
	 *
	 * <p>소리는 {@code TrialWarning.playEach} 로 낸다 — 팀원 루프 안에서 {@code level.playSound}
	 * 를 부르면 <b>사람 수만큼 겹친다</b>(그 메서드 설명의 「열일곱 자리가 전부 같은 방식으로
	 * 틀려 있었다」).
	 *
	 * <p>소리를 둘 겹치는 것이 이 연출의 뼈대다. 낮은 울음은 <b>누가</b>(드래곤), 높은 종은
	 * <b>무엇을</b>(무언가 채워졌다) 말한다. 하나로는 「맞았나?」와 구별되지 않는다.
	 *
	 * @param members 지금 엔드에 선 팀원. 비어 있으면 아무 일도 하지 않는다 — 볼 사람이 없는
	 *                연출에 파티클을 뿌릴 이유가 없고, 카드는 이 연출과 무관하게 이미 걸린다
	 */
	public static void play(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members) {
		if (end == null || members == null || members.isEmpty()) {
			return;
		}

		// 낮은 울음 — 「드래곤이 한 일이다」.
		TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.5F);
		// 높은 종 — 「무언가 채워졌다」. 바닐라에서 리스폰 정박기에 충전이 한 칸 차는 소리다.
		// 시련의 다른 소리와 겹치지 않는 것을 골랐다 — 경고 세 층은 드래곤 울음·신호기·경험치
		// 구슬을 쓰고 있다(TrialWarning.cueOf).
		TrialWarning.playEach(end, members, SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.9F, 1.4F);
		TrialWarning.shakeEach(members);

		if (dragon != null && dragon.isAlive()) {
			Vec3 at = dragon.position();
			ring(end, at, DRAGON_RING_RADIUS, DRAGON_RING_POINTS);
			// 위로 솟는 한 다발. 개수를 세는 형태라 꾸러미 한 장이다.
			end.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, true, false, at.x, at.y, at.z, 60,
					1.5, 1.0, 1.5, 0.08);
			// ⚠ FLASH 는 쓰지 않는다. 26.3 에서 SimpleParticleType 이 아니라
			// ParticleType<ColorParticleOption> 이라 옵션 객체가 필요하다. 「크게 한 번」은
			// SONIC_BOOM 으로 낸다.
			end.sendParticles(ParticleTypes.SONIC_BOOM, true, false, at.x, at.y, at.z, 1,
					0.0, 0.0, 0.0, 0.0);
		}

		// 발밑에도 한 번 — 드래곤이 아레나 반대편에 있어도 「나에게도 일어났다」가 보인다.
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			Vec3 feet = member.position();
			ring(end, feet.add(0.0, GROUND_OFFSET, 0.0), FOOT_RING_RADIUS, FOOT_RING_POINTS);
		}
	}

	/**
	 * 수평 고리 하나.
	 *
	 * <p>긴 형식으로 보낸다 — 짧은 형식은 <b>발생 지점에서 32칸 안</b>에만 나가고, 드래곤은 그보다
	 * 훨씬 높이 떠 있다({@code TrialWarning} 클래스 설명의 「거리 제한을 끄고 보낸다」).
	 *
	 * <p>색은 {@code DEADLY}(빨강)를 쓰지 않는다. 그 색은 「서 있으면 죽는다」라는 뜻을 이미
	 * 배운 색이라, 피해를 예고하지 않는 이 고리에 쓰면 팀이 비킬 곳을 찾는다. {@code MARKED}
	 * (보라)는 「너 하나를 노린다」인데 그것도 아니다 — 여기서는 규약이 아니라 <b>엔드의 색</b>
	 * 으로 쓴다. 규약에 새 뜻을 만들지 않으려고 이미 있는 색에서 고른 것이다.
	 */
	private static void ring(ServerLevel end, Vec3 center, double radius, int points) {
		for (int index = 0; index < points; index++) {
			double angle = (Math.PI * 2.0 * index) / points;
			end.sendParticles(TrialWarning.dust(TrialWarning.Colors.MARKED), true, false,
					center.x + Math.cos(angle) * radius,
					center.y,
					center.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}
}

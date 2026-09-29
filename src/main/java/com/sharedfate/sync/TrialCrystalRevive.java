package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link TrialCatalog.Risk.CrystalRevive} 실행기 — 크리스탈이 되살아난다.
 *
 * <h2>이 카드는 한 번 터지는 것이 아니라 「연출 → 부활」 한 짝이다</h2>
 *
 * <p>다른 카드는 주기마다 반복하지만 이것은 <b>카드당 한 번</b>이다. 카드가 쌓여 있는 내내
 * 크리스탈이 계속 되살아나면 전투가 끝나지 않는다 — 공유 체력에 전멸이 곧 월드 삭제인 게임에서
 * 「끝나지 않는 전투」는 난이도가 아니라 고장이다. 그래서 {@link #SPENT} 가 이미 쓴 카드를
 * 기억하고, 같은 카드는 두 번 발동하지 않는다.
 *
 * <p>다만 <b>반복형으로 늘릴 자리는 남겨 두었다.</b> 개수도 연출 길이도 {@code Risk} 값에서
 * 오므로, 언젠가 {@code interval} 을 가진 반복형 부활 카드를 만들 때 손댈 곳은 {@link #SPENT}
 * 의 열쇠에 주기 번호({@link TrialRisks#strikeIndex})를 더하는 한 줄뿐이다. 실행·연출·안전
 * 장치는 그대로 쓴다.
 *
 * <h2>연출 동안 왜 무적으로 만들지 않는가</h2>
 *
 * <p>이 카드의 대가는 「되살아난 크리스탈을 다시 깨야 한다」가 아니라 <b>그 동안 드래곤을 때릴
 * 수 없다</b>는 것이다. 그것을 무적 깃발로 구현하면 <b>때려도 아무 일이 없다</b> — 검이 지나가고
 * 화살이 박히는데 체력이 안 깎이는 것은 플레이어 눈에 언제나 버그다. 대신 드래곤을 <b>가장 높은
 * 기둥보다 더 위, 중앙 상공으로 끌어올린다.</b> 못 때리는 이유가 화면에 그대로 보이고, 그래도
 * 닿은 한 발은 정직하게 들어간다. 「올려다보며 기다린다」가 카드 설명과도 맞는다.
 *
 * <h2>위상(phase)을 바꾸지 않는다</h2>
 *
 * <p>{@code EnderDragonPhase.HOVERING} 으로 바꾸면 <b>우리가 되돌려 놓아야 한다.</b> 그런데
 * 연출 도중에 드래곤이 죽을 수도, 서버가 내려갈 수도, 팀이 전멸해 월드가 갈릴 수도 있다. 되돌릴
 * 기회를 한 번이라도 놓치면 드래곤이 공중에 선 채로 남아 전투가 영영 끝나지 않는다. 그래서
 * <b>매 틱 자리만 당겨 둔다.</b> 우리가 부르기를 멈추는 순간 바닐라 비행이 그대로 이어받으므로
 * 되돌릴 것이 아예 없다 — 어정쩡하게 남을 상태가 없는 것이 이 방식의 값어치다.
 *
 * <p>같은 이유로 서버가 연출 도중 내려가면 다시 떴을 때 {@code now - granted} 가 이미
 * {@code showTicks} 를 넘어 있어 곧바로 부활한다. 연출을 못 본 것은 손해지만, <b>저장 파일에
 * 「연출 중」 상태가 남지 않는다.</b>
 *
 * <h2>부활이 사람을 죽여서는 안 된다</h2>
 *
 * <p>크리스탈이 되살아나는 것은 <b>시간 손실</b>이지 피해가 아니다. 그런데 엔드 크리스탈은
 * 26.3 에서도 부서질 때 위력 6 의 폭발을 일으키고({@code EndCrystal.hurtServer}), 살아 있는
 * 동안 제 발밑 칸에 불을 놓는다({@code EndCrystal.tick} 이 드래곤 전투가 열려 있으면
 * {@code BaseFireBlock.getState} 를 깐다). 기둥 꼭대기에 서 있는 사람 발밑에 그것을 생성하면
 * 불에 타고, 나중에 그 크리스탈이 터질 때 허공으로 날아간다. 그래서 <b>사람이 서 있는 자리는
 * 건너뛴다.</b> 밀어내지 않는 이유는 기둥 아래가 허공이라 밀어내는 것이 곧 낙사이기 때문이다.
 * 연출 내내 그 자리에 빨간 고리가 떠 있으므로 비키라는 말은 충분히 했다.
 *
 * <h2>빔 대상은 쓰지 않는다</h2>
 *
 * <p>{@code EndCrystal.setBeamTarget} 은 <b>고정 블록</b>을 가리키는 부활 의식용 선이라 한 번
 * 걸면 우리가 지우기 전까지 남는다. 드래곤을 가리키게 걸어 두면 연출이 끝난 뒤 하늘의 빈 점을
 * 향한 선이 열 개 남는다. 되살아난 크리스탈과 드래곤을 잇는 회복 빔은 <b>바닐라가 이미</b>
 * 가장 가까운 크리스탈에 대해 그린다. 연출의 선은 파티클로 직접 긋는다.
 */
public final class TrialCrystalRevive {

	/** 자리에 그리는 고리의 반경. 이 안에 사람이 있으면 그 자리는 건너뛴다. */
	private static final double SEAT_RADIUS = 2.0;
	/** 자리 위아래로 이만큼까지 「그 자리에 있다」로 본다. 기둥 위에서 뛰어도 걸리게 넉넉히 잡는다. */
	private static final double SEAT_HEADROOM = 3.0;
	/** 이 거리 안에 크리스탈이 있으면 그 자리는 이미 차 있다. 자리와 개체 좌표는 정확히 같다. */
	private static final double SEAT_TAKEN_REACH = 2.0;

	/**
	 * 가장 높은 기둥 위로 드래곤을 얼마나 더 띄우는가.
	 *
	 * <p>활의 화살은 30 블록쯤 날아가면 눈에 띄게 떨어진다. 기둥 꼭대기에 올라선 사람 기준으로도
	 * 닿지 않을 만큼 띄우되, 화면 밖으로 사라져 「드래곤이 어디 갔지」가 되지는 않는 높이다.
	 */
	private static final double WATCH_CLEARANCE = 30.0;
	/** 매 틱 목표 자리로 당기는 비율. 1 이면 순간이동이라 연출이 아니라 사고처럼 보인다. */
	private static final double WATCH_PULL = 0.2;
	/** 이만큼 가까워지면 정확히 그 점에 붙인다. 붙이지 않으면 드래곤 비행과 줄다리기가 된다. */
	private static final double WATCH_SNAP = 1.0;

	/** 연출을 다시 그리는 간격(틱). 매 틱 그리면 자리 열 곳의 고리만으로 파티클 패킷이 넘친다. */
	private static final int PULSE_TICKS = 4;
	/** 선 하나에 찍는 점 수. 거리와 무관하게 고정이다 — 멀면 성겨질 뿐 선은 읽힌다. */
	private static final int BEAM_POINTS = 8;

	/** 이번 틱에 할 일. 월드를 만지기 전에 이것만으로 정해진다. */
	enum Step {
		/** 이 카드는 이미 썼다. */
		NOTHING,
		/** 연출 중. 드래곤을 상공에 붙들고 선을 긋는다. */
		SHOW,
		/** 지금 되살린다. 카드당 딱 한 틱만 나온다. */
		REVIVE
	}

	/**
	 * 이미 발동한 카드들.
	 *
	 * <p><b>열쇠에 주의.</b> 공개 진입점이 카드 id 를 받지 않으므로 「받은 틱 + 위험 값」으로
	 * 열쇠를 만든다. 값이 완전히 같은 부활 위험 둘을 한 카드에 걸면 두 번이 한 번으로 합쳐지는데,
	 * 그때는 진입점에 열쇠 인자를 더해야 한다 — {@link TrialRisks} 가 {@code id + '#' + index} 로
	 * 푼 것과 같은 문제다.
	 *
	 * <p>정적이라 월드보다 오래 산다. {@link #clearState()} 를 부르지 않으면 <b>다음 판에서 같은
	 * 틱에 받은 카드가 죽은 카드가 된다.</b>
	 */
	private static final Set<Key> SPENT = new HashSet<>();

	private record Key(long granted, TrialCatalog.Risk.CrystalRevive risk) {
	}

	private TrialCrystalRevive() {
	}

	/**
	 * 매 틱.
	 *
	 * <p>{@code members} 가 비어 있어도 돈다. 되살아나는 것은 사람이 보고 있든 아니든 전투의
	 * 사실이고, 여기서 물러서면 전원이 잠깐 접속을 끊는 것으로 카드를 지울 수 있게 된다.
	 *
	 * @param granted 카드를 받은 틱. 연출은 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, long granted, long now,
			@Nullable TrialCatalog.Risk.CrystalRevive risk) {
		if (end == null || risk == null || risk.count() <= 0) {
			return;
		}

		Step step = step(granted, now, risk);
		if (step == Step.NOTHING) {
			return;
		}

		// 빈 자리는 매 틱 다시 센다. 연출 도중에 사람이 크리스탈을 하나 더 깨면 그 자리도 대상이 된다.
		List<Vec3> targets = pickSeats(openSeats(EndPillars.crystalSeats(end), livingCrystals(end)),
				risk.count());

		if (step == Step.SHOW) {
			show(end, dragon, members, targets, granted, now, risk);
			return;
		}

		// 드래곤이 없으면 되살리지 않는다. 주인 없는 크리스탈만 기둥에 남기면 다음 전투의 판이
		// 달라지고, 되살아난 것이 회복시킬 대상도 없다. 카드는 그대로 소모한다 — 연출은 이미 돌았고,
		// 드래곤이 죽었다는 것은 이 전투가 끝났다는 뜻이다.
		if (dragon == null || !dragon.isAlive()) {
			return;
		}
		// 서 있는 사람은 여기서 뺀다. 연출에서는 그 자리에도 고리를 그렸다 — 비키라고 말하려면
		// 먼저 보여 줘야 하기 때문이다.
		List<Vec3> safe = withoutOccupied(targets, bystanders(end));
		int born = revive(end, safe);
		if (born <= 0) {
			return;
		}
		heal(dragon, risk.heal(), born);
		announce(end, dragon, members, born);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>드래곤에 남겨 둔 것이 없으므로 여기서 되돌릴 것도 없다. 지우는 것은 「이 카드를 이미
	 * 썼다」는 기억뿐이고, 그것을 안 지우면 다음 판의 카드가 조용히 죽는다.
	 */
	public static void clearState() {
		SPENT.clear();
	}

	// ------------------------------------------------------------------ 연출

	/**
	 * 연출 한 틱. 드래곤을 상공에 붙들고 자리마다 선과 고리를 그린다.
	 *
	 * <p>경고의 세 층은 {@link TrialWarning} 의 것을 그대로 쓴다. 이 카드만 다른 신호를 쓰면
	 * 플레이어가 카드 수만큼 새 언어를 배워야 한다. 다만 여기서 세 층이 뜻하는 것은 「맞는다」가
	 * 아니라 <b>「이 자리가 곧 막힌다」</b>다 — 색은 같은 빨강이고, 요구하는 행동도 같은
	 * 「그 자리에서 비켜라」이므로 뜻이 어긋나지 않는다.
	 */
	private static void show(ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, List<Vec3> targets, long granted, long now,
			TrialCatalog.Risk.CrystalRevive risk) {
		Vec3 perch = watchPoint(EndPillars.tops(end), WATCH_CLEARANCE);
		if (dragon != null && dragon.isAlive() && perch != null) {
			hold(dragon, perch);
		}
		if (targets.isEmpty()) {
			// 되살릴 자리가 하나도 없다. 드래곤은 그래도 올라가 있다 — 연출 길이만큼 못 때리는 것이
			// 이 카드의 대가이고, 그 대가는 결과와 무관하게 치러야 앞뒤가 맞는다.
			return;
		}

		int remaining = remainingShowTicks(now, granted, risk.showTicks());
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage != null && TrialRisks.stageJustChanged(remaining)) {
			for (Vec3 seat : targets) {
				TrialWarning.sound(end, seat, stage);
			}
		}
		if (remaining == TrialWarning.TICKS_REPOSITION && members != null) {
			// 「지정한 자리로 옮겨야 할 때」의 예고 시간이다. 기둥 위에 있는 사람은 사다리를 타고
			// 내려와야 하므로 한 걸음 옆으로 비키는 것보다 오래 걸린다.
			TrialWarning.shout(members, Component.literal("기둥 위에서 내려오십시오 — 크리스탈이 되살아납니다"));
		}

		if (TrialRisks.elapsedSinceGrant(now, granted) % PULSE_TICKS != 0L) {
			return;
		}
		float progress = showProgress(now, granted, risk.showTicks());
		Vec3 source = dragon == null ? perch : dragon.position();
		for (Vec3 seat : targets) {
			if (stage != null && stage != TrialWarning.Stage.APPROACH) {
				TrialWarning.markGround(end, seat, SEAT_RADIUS);
			}
			if (source != null) {
				drawBeam(end, source, seat, progress);
			}
		}
	}

	/**
	 * 드래곤을 목표 자리로 당긴다.
	 *
	 * <p>당기기만 하고 속도를 0 으로 눌러 둔다. 비행 AI 는 매 틱 제 목표를 향해 속도를 다시
	 * 계산하므로 우리가 이기는 유일한 방법은 <b>매 틱 자리를 다시 쓰는 것</b>이다. 가까워지면
	 * 정확히 붙이는 것이 핵심이다 — 비율로만 당기면 AI 가 밀어내는 만큼 떨어진 곳에서 균형이
	 * 잡혀, 드래곤이 사람 쪽으로 조금씩 흘러내린다.
	 */
	private static void hold(EnderDragon dragon, Vec3 perch) {
		Vec3 next = towards(dragon.position(), perch, WATCH_PULL, WATCH_SNAP);
		dragon.setPos(next.x, next.y, next.z);
		dragon.setDeltaMovement(Vec3.ZERO);
	}

	/**
	 * 드래곤에서 자리로 선을 긋는다.
	 *
	 * <p>선은 <b>진행도만큼만</b> 자란다. 처음부터 끝까지 이어 두면 「드래곤과 기둥이 실로 묶였다」
	 * 로만 보이고 언제 끝나는지 알 수 없다. 자라는 선은 닿는 순간이 곧 부활이라 초읽기 노릇을 한다.
	 */
	private static void drawBeam(ServerLevel end, Vec3 from, Vec3 to, float progress) {
		for (Vec3 point : beamPoints(from, to, progress, BEAM_POINTS)) {
			end.sendParticles(TrialWarning.dust(TrialWarning.Colors.DEADLY),
					point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	// ------------------------------------------------------------------ 부활

	/**
	 * 자리마다 크리스탈을 하나씩 놓는다.
	 *
	 * <p>바닐라와 <b>같은 좌표·같은 회전</b>으로 둔다({@code EndSpikeFeature} 가 하는 것과 같다).
	 * 처음 생성된 크리스탈과 구분이 가면 조준이 달라지고, 플레이어가 「부활한 것은 다르다」는
	 * 없는 규칙을 배우게 된다.
	 *
	 * <p>{@code setPermanentlyInvulnerable} 을 켜지 않는다. 그것은 드래곤 재소환 의식용 크리스탈의
	 * 것이고, 여기서 켜면 되살아난 것을 깰 방법이 없어 전투가 끝나지 않는다.
	 *
	 * @return 실제로 생긴 개수
	 */
	private static int revive(ServerLevel end, List<Vec3> seats) {
		int born = 0;
		for (Vec3 seat : seats) {
			EndCrystal crystal = new EndCrystal(end, seat.x, seat.y, seat.z);
			crystal.setYRot(end.getRandom().nextFloat() * 360.0F);
			if (!end.addFreshEntity(crystal)) {
				continue;
			}
			born++;
			// 폭발 파티클을 쓰지 않는다. 이 카드에서 폭발은 「크리스탈이 깨졌다」는 뜻이라 정반대로 읽힌다.
			end.sendParticles(ParticleTypes.END_ROD, seat.x, seat.y + 0.5, seat.z, 24,
					0.2, 0.4, 0.2, 0.05);
			end.sendParticles(ParticleTypes.REVERSE_PORTAL, seat.x, seat.y + 0.5, seat.z, 40,
					0.5, 0.8, 0.5, 0.08);
			end.playSound(null, seat.x, seat.y, seat.z, SoundEvents.END_PORTAL_FRAME_FILL,
					SoundSource.HOSTILE, 3.0F, 0.8F);
		}
		return born;
	}

	/**
	 * 되살아난 개수만큼 드래곤을 회복시킨다.
	 *
	 * <p>카드에 적힌 값은 <b>0</b>이다. 바닐라 크리스탈이 살아 있는 동안 이미 드래곤을 회복시키므로
	 * 여기서 또 주면 같은 값을 두 번 받는다. 그래도 칸을 남겨 둔 것은 「되살리되 회복은 없다」와
	 * 「되살리고 즉시 회복까지」가 서로 다른 카드가 될 수 있기 때문이다.
	 */
	private static void heal(EnderDragon dragon, float perCrystal, int born) {
		if (!(perCrystal > 0.0F) || born <= 0) {
			return;
		}
		dragon.heal(perCrystal * born);
	}

	/** 끝났다고 알린다. 소리는 <b>드래곤 자리</b>에서 낸다 — 이 일을 한 것은 드래곤이다. */
	private static void announce(ServerLevel end, EnderDragon dragon,
			@Nullable List<ServerPlayer> members, int born) {
		Vec3 at = dragon.position();
		end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN,
				SoundSource.HOSTILE, 1.0F, 1.2F);
		if (members != null) {
			TrialWarning.shout(members, Component.literal("크리스탈 " + born + "개가 되살아났습니다"));
		}
		SharedFateMod.LOGGER.info("[END] 「부활」 — 크리스탈 {}개가 되살아났습니다", born);
	}

	// ------------------------------------------------------------------ 월드에서 읽어 오는 것

	/** 지금 살아 있는 크리스탈들의 자리. 철장에 갇힌 것도 자리를 차지한다. */
	private static List<Vec3> livingCrystals(ServerLevel end) {
		List<Vec3> taken = new ArrayList<>();
		for (EndCrystal crystal : end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive)) {
			taken.add(crystal.position());
		}
		return taken;
	}

	/**
	 * 자리를 막을 수 있는 사람들.
	 *
	 * <p>팀원 목록이 아니라 <b>차원에 있는 사람 전부</b>를 본다. 팀에 없는 사람이 기둥 위에 있다고
	 * 발밑에 크리스탈을 생성해도 되는 것은 아니다. 관전자는 뺀다 — 불에 타지도 폭발에 밀리지도
	 * 않는데 자리를 막으면 그것이야말로 버그로 보인다.
	 */
	private static List<Vec3> bystanders(ServerLevel end) {
		List<Vec3> standing = new ArrayList<>();
		for (ServerPlayer player : end.players()) {
			if (player.isSpectator()) {
				continue;
			}
			standing.add(player.position());
		}
		return standing;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 이번 틱에 무엇을 할 차례인가. <b>{@link Step#REVIVE} 를 돌려주면서 카드를 소모한다.</b>
	 *
	 * <p>소모를 여기서 하는 이유는 「돌려주고 나서 호출자가 잊는」 길을 막기 위해서다. 부활 틱을
	 * 두 번 돌려주는 순간 크리스탈이 스무 개가 되고, 그 사고는 실제 전투에서만 드러난다.
	 *
	 * <p>자리가 하나도 비어 있지 않아 실제로 아무것도 생기지 않아도 카드는 소모된다. 연출은 이미
	 * 돌았고 드래곤은 그 시간 동안 하늘에 있었다 — 대가를 치렀으면 카드도 끝난 것이다. 남겨 두면
	 * 한참 뒤 사람이 크리스탈을 깨는 순간 예고 없이 되살아난다.
	 */
	static Step step(long granted, long now, TrialCatalog.Risk.CrystalRevive risk) {
		Key key = new Key(granted, risk);
		if (SPENT.contains(key)) {
			return Step.NOTHING;
		}
		if (!revivesAt(now, granted, risk.showTicks())) {
			return Step.SHOW;
		}
		SPENT.add(key);
		return Step.REVIVE;
	}

	/**
	 * 지금이 되살아나는 틱인가.
	 *
	 * <p>{@code showTicks} 가 0 이하면 연출 없이 받은 그 틱에 바로다. 복원 직후 {@code now} 가
	 * 받은 틱보다 작을 수 있으므로 흐른 시간은 {@link TrialRisks#elapsedSinceGrant} 로 잰다 —
	 * 그냥 빼면 음수가 나와 「아직 연출 중」으로 영영 남는다.
	 */
	static boolean revivesAt(long now, long granted, int showTicks) {
		return TrialRisks.elapsedSinceGrant(now, granted) >= Math.max(0, showTicks);
	}

	/** 부활까지 남은 틱. 0 이면 이번 틱이다. */
	static int remainingShowTicks(long now, long granted, int showTicks) {
		if (showTicks <= 0) {
			return 0;
		}
		long left = showTicks - TrialRisks.elapsedSinceGrant(now, granted);
		return (int) Math.max(0L, Math.min(showTicks, left));
	}

	/**
	 * 연출이 얼마나 진행됐는가. 0 이 시작, 1 이 부활하는 순간.
	 *
	 * <p><b>0 과 1 을 벗어나면 안 된다.</b> 이 값이 선의 길이라, 1 을 넘으면 선이 자리를 지나쳐
	 * 허공으로 뻗고 음수면 드래곤 뒤쪽으로 뻗는다. 둘 다 「어디에 생기는지」를 거짓말하는 것이다.
	 */
	static float showProgress(long now, long granted, int showTicks) {
		if (showTicks <= 0) {
			return 1.0F;
		}
		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		if (elapsed >= showTicks) {
			return 1.0F;
		}
		return (float) elapsed / showTicks;
	}

	/**
	 * 아직 크리스탈이 없는 자리들.
	 *
	 * <p>살아 있는 자리를 건너뛰는 것이 이 카드가 「되살린다」인 이유다. 겹쳐 놓으면 크리스탈이
	 * 두 겹으로 서고, 하나를 깨면 옆 것이 연쇄로 터져 기둥 위가 통째로 날아간다.
	 */
	static List<Vec3> openSeats(List<Vec3> seats, List<Vec3> taken) {
		List<Vec3> open = new ArrayList<>();
		for (Vec3 seat : seats) {
			if (!nearAny(seat, taken, SEAT_TAKEN_REACH, SEAT_TAKEN_REACH)) {
				open.add(seat);
			}
		}
		return open;
	}

	/**
	 * 사람이 서 있는 자리를 뺀다.
	 *
	 * <p>연출 대상에서 빼는 것이 아니라 <b>생성 직전에</b> 뺀다. 연출에서 미리 빼면 그 사람만
	 * 자기 발밑에 아무 표시도 못 보고, 그러면 비킬 이유를 알 수 없다.
	 */
	static List<Vec3> withoutOccupied(List<Vec3> seats, List<Vec3> standing) {
		List<Vec3> safe = new ArrayList<>();
		for (Vec3 seat : seats) {
			if (!nearAny(seat, standing, SEAT_RADIUS, SEAT_HEADROOM)) {
				safe.add(seat);
			}
		}
		return safe;
	}

	/** 가로 반경과 세로 높이를 따로 본다. 자리는 바닥의 원이고 기둥 위아래는 사정이 아주 다르다. */
	private static boolean nearAny(Vec3 seat, List<Vec3> others, double radius, double headroom) {
		for (Vec3 other : others) {
			if (Math.abs(other.y - seat.y) <= headroom
					&& TrialRisks.insideMark(other, seat, radius)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 되살릴 자리를 개수만큼 집는다.
	 *
	 * <p>고르지 않고 목록 순서대로 집는다. 지금 카드는 기둥 수만큼(10) 되살리므로 고를 일 자체가
	 * 없다. <b>개수가 적은 카드를 만들 때 여기에 고르는 규칙을 더하라 — 그때는 반드시 카드당 한 번만
	 * 굴려야 한다.</b> 매 틱 굴리면 연출의 선이 기둥 사이를 뛰어다녀 어디에 생기는지 아무도 모른다.
	 */
	static List<Vec3> pickSeats(List<Vec3> open, int count) {
		if (count <= 0) {
			return List.of();
		}
		return List.copyOf(open.subList(0, Math.min(count, open.size())));
	}

	/**
	 * 드래곤이 머무는 중앙 상공.
	 *
	 * <p>좌표를 박지 않는다. 기둥 배치도 높이도 <b>월드 시드에서 나오므로</b> 어느 판에서 재어 적은
	 * 숫자는 다음 판에서 기둥 속이거나 화살 사거리 안이다. 가로는 기둥들의 한가운데, 세로는 가장
	 * <b>높은</b> 기둥 위다 — 평균을 쓰면 제일 높은 기둥 꼭대기에 올라선 사람이 드래곤과 같은
	 * 높이에 서게 된다.
	 *
	 * @return 기둥이 하나도 없으면 {@code null}. 그때는 붙들 자리가 없으니 붙들지 않는다
	 */
	static @Nullable Vec3 watchPoint(List<Vec3> tops, double clearance) {
		if (tops.isEmpty()) {
			return null;
		}
		double x = 0.0;
		double z = 0.0;
		double highest = tops.getFirst().y;
		for (Vec3 top : tops) {
			x += top.x;
			z += top.z;
			highest = Math.max(highest, top.y);
		}
		return new Vec3(x / tops.size(), highest + Math.max(0.0, clearance), z / tops.size());
	}

	/**
	 * 다음 틱에 놓을 자리.
	 *
	 * <p>{@code snap} 이 있어야 수렴한다. 비율로만 당기면 남은 거리에 비례해 당기는 힘이 줄어
	 * 드래곤 비행 속도와 균형을 이루는 지점에서 멈추고, 그 지점은 드래곤이 노리는 사람 쪽으로
	 * 치우친다 — 「중앙 상공에서 지켜본다」가 「사람 쪽으로 조금씩 내려온다」가 된다.
	 */
	static Vec3 towards(Vec3 from, Vec3 to, double pull, double snap) {
		if (from.distanceToSqr(to) <= Math.max(0.0, snap) * Math.max(0.0, snap)) {
			return to;
		}
		return from.lerp(to, Math.max(0.0, Math.min(1.0, pull)));
	}

	/**
	 * 지금까지 그려야 할 선 위의 점들.
	 *
	 * <p>{@code progress} 를 잘라 두는 것은 안전장치가 아니라 <b>규칙</b>이다. 선의 끝이 곧
	 * 「여기에 생긴다」라 선분 밖으로 나가면 예고가 거짓말이 된다.
	 */
	static List<Vec3> beamPoints(Vec3 from, Vec3 to, float progress, int points) {
		if (points <= 0) {
			return List.of();
		}
		double grown = Math.max(0.0F, Math.min(1.0F, progress));
		Vec3 head = from.add(to.subtract(from).scale(grown));
		List<Vec3> line = new ArrayList<>(points);
		for (int index = 0; index < points; index++) {
			double along = (double) (index + 1) / points;
			line.add(from.add(head.subtract(from).scale(along)));
		}
		return line;
	}
}

package com.sharedfate.sync;

import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 엔더 드래곤을 잡은 <b>승리 팀</b>을 찾는다.
 *
 * <h2>왜 따로 떼어 냈는가 — 간접 처치로 승리 팀이 통째로 사라졌다</h2>
 * <p>{@code RunProgressManager.onDeath} 는 처치자를 {@code dragon.getLastHurtByPlayer()}
 * 하나로만 찾았다. 그런데 바닐라 {@code LivingEntity.resolvePlayerResponsibleForDamage} 는
 * <b>{@code DamageSource.getEntity()}(= {@code causingEntity})가 사람일 때만</b>
 * {@code lastHurtByPlayer} 를 적어 둔다(26.2·26.3 바이트코드가 같다). 한편 드래곤은
 * <b>폭발이면 누가 터뜨렸든 피해를 받는다</b> — {@code EnderDragon.hurt} 가
 * {@code getEntity() instanceof Player} 이거나 피해 유형이
 * {@code #minecraft:always_hurts_ender_dragons} 면 통과시키는데, 26.3 에서 그 태그의 내용이
 * 정확히 {@code #minecraft:is_explosion} 하나다.
 *
 * <p>그래서 <b>주인이 없는 폭발</b>이 드래곤을 잡으면 피해는 들어가는데 기억되는 사람은 없다.
 * 실제로 이렇게 끝난 회차의 로그가 이렇다.
 * <pre>
 * 승리! '모험가' 팀이 1회차에서 엔더 드래곤을 처치했습니다!
 * [RUN] victory runNumber=1 team=모험가 celebrationPlayers=1
 * </pre>
 * 「모험가」는 처치자도 팀도 못 찾았을 때만 나오는 이름이고, 그 회차는 <b>승리 책도 회차
 * 기록도 통째로 빠진다</b>({@code DamageLedger.noteRunEnd} 와 {@code giveVictoryBooks} 가
 * 둘 다 팀을 받아야 움직인다).
 *
 * <h3>이 판정이 태어난 경로는 침대였다 — ⚠ <b>그 길은 2026-10-04 에 막혔다</b></h3>
 *
 * <p>⚠⚠ <b>아래 한 단락은 과거형이다. 판정 코드는 그대로 둘 것</b> — 막힌 것은 <b>침대 하나</b>
 * 뿐이고 주인 없는 폭발이 오는 길은 그대로 남아 있다(다음 단락).
 *
 * <p>엔드에서 <b>침대를 터뜨려</b> 드래곤을 잡는 것은 실전에서 가장 흔한 전술<b>이었고</b>, 이
 * 네 단계가 그 전제로 세워졌다. 26.3 {@code BedBlock} 은 폭발을 이렇게 만들었다.
 * <pre>
 * level.explode(null, damageSources().badRespawnPointExplosion(위치), null, …)
 *               ────                  ──────────────────────────────
 *            소스 엔티티 없음         엔티티를 아예 받지 않는 피해 원인
 * </pre>
 * <p>{@code getEntity()} 도 {@code getDirectEntity()} 도 비어 있어 <b>1·2·3단계가 전부
 * 비었다.</b> 그러면서 {@code BAD_RESPAWN_POINT} 는 {@code #is_explosion} 에 들어 있어 드래곤에게
 * 피해는 그대로 들어갔다. <b>「피해는 들어가는데 기억될 사람이 아무도 없는」 모양이 거기서
 * 완성됐다</b> — 4단계가 없으면 침대로 잡은 회차는 반드시 기록을 잃었다.
 *
 * <p>⚠ <b>엔드 침대 길은 2026-10-04 에 막혔다.</b> 사람이 「엔더에서 침대 터뜨렷을떄 터지지않게
 * 하자」라고 해서 {@code BedBlockEndBlastMixin} 이 <b>엔드에서만</b> 그 폭발을 삼킨다(침대 자신은
 * 그대로 사라지고, 네더는 바닐라 그대로다). 곧 <b>위 단락의 전제는 이제 참이 아니다.</b>
 *
 * <h3>⚠⚠ 그래도 4단계는 필요하다 — 주인 없는 폭발이 오는 길이 남아 있다</h3>
 *
 * <p>남은 것은 <b>명령으로 소환한 TNT</b>, <b>연쇄 점화로 소유자를 잃은 TNT</b>, 그리고 이 모드
 * 자신의 「폭발 교환」({@code PositionSwapManager} 이 주인 없이 터뜨린다)이다. 셋 다 엔드에서
 * 드래곤을 때릴 수 있고 셋 다 1·2·3단계를 똑같이 비운다. <b>침대가 막혔으니 이 판정도 지워도
 * 된다고 읽으면 안 된다</b> — 가장 흔한 입구가 닫혔을 뿐 사각지대 자체는 그대로다. 「한쪽만
 * 막으면 반드시 샌다」가 이 저장소의 제1 함정이고, 여기서는 <b>막은 쪽이 아니라 받는 쪽</b>이
 * 그 보험이다.
 *
 * <p><b>엔드 크리스탈은 대개 여기 걸리지 않는다.</b> {@code EndCrystal.hurtServer} 가
 * {@code source.getEntity() != null} 일 때 {@code explosion(크리스탈, 깬 사람)} 을 만들어
 * 넘기므로, 사람이 깬 크리스탈은 1단계에서 잡힌다. 크리스탈이 <b>사람 아닌 원인</b>으로
 * 터졌을 때만 주인 없는 폭발이 되고, 크리스탈은 {@code OwnableEntity} 가 아니라 2단계로도
 * 구제되지 않아 4단계가 받는다.
 *
 * <h2>찾는 순서 — 앞에서 찾으면 뒤는 보지 않는다</h2>
 * <ol>
 *   <li>{@code source.getEntity()} 가 사람({@link Outcome#CAUSING_PLAYER}). 직접 때린 경우와
 *       화살·폭발처럼 소유자가 제대로 실린 경우가 전부 여기서 끝난다.</li>
 *   <li>{@code source.getDirectEntity()} 의 <b>간접 소유자</b>가 사람
 *       ({@link Outcome#INDIRECT_OWNER}). 바닐라 {@code Explosion.getIndirectSourceEntity}
 *       를 그대로 쓴다 — 투사체·TNT 처럼 소유자를 들고 있는 엔티티를 한 단계 푼다.</li>
 *   <li>{@code dragon.getLastHurtByPlayer()}({@link Outcome#LAST_HURT_BY_PLAYER}). 예전부터
 *       보던 자리다. 마지막 한 방이 주인 없는 폭발이어도 그 전에 사람이 때렸다면 여기서
 *       나온다.</li>
 *   <li>그래도 못 찾았으면 <b>회차를 시작한 팀이 정확히 하나일 때만</b> 그 팀
 *       ({@link Outcome#SOLE_STARTED_TEAM}). 이 모드는 {@code singleTeamOnly} 가 기본값이라
 *       ({@code TeamManager.canCreateNewTeam}) 사실상 언제나 팀이 하나이고, 그 팀이 아니면
 *       드래곤을 잡을 사람이 아예 없다.</li>
 * </ol>
 *
 * <p><b>팀이 둘 이상이면 절대 찍지 않는다.</b> 남이 잡은 드래곤으로 이기는 길이 열리는 것이
 * 「모험가」로 끝나는 것보다 훨씬 나쁘다. 못 찾으면 {@link Outcome#UNKNOWN} 을 돌려주고
 * 부르는 쪽이 예전처럼 「모험가」로 남긴다.
 *
 * <h2>사람을 찾았으면 거기서 멈춘다 — 4단계로 넘어가지 않는다</h2>
 * <p>1~3단계에서 사람이 나왔는데 그 사람이 팀에 속하지 않았다면 <b>팀은 없는 채로 끝난다.</b>
 * 4단계로 흘려보내지 않는다. 처치자를 이미 알아냈으므로 찍을 근거가 없고, 찍으면 운영자가
 * 시험 삼아 잡은 드래곤이 남의 회차를 끝내 버린다. 예전 동작도 이와 같았다 — 팀 없는 사람이
 * 잡으면 그 사람 이름으로 승리했다.
 *
 * <h2>「아직 시작하지 않은 팀」 거부는 여기 있다</h2>
 * <p>예전에는 {@code RunProgressManager.onDeath} 가 직접 {@code GameStartManager.waiting} 을
 * 보고 걸렀다. 판정이 네 단계로 늘어난 지금 그 검사를 밖에 두면, 4단계가 이미 품고 있는 같은
 * 규칙(시작한 팀만 센다)과 두 곳으로 갈라져 언젠가 어긋난다. 그래서 규칙을 이 클래스 안으로
 * 모으고, 부르는 쪽은 {@link Resolution#victory()} 하나만 보면 되게 했다.
 *
 * <p>거부는 <b>「건너뛰고 다음 단계」가 아니라 거부</b>다. 대기 중인 팀이 잡았으면 회차는
 * 끝나지 않는다 — 곁에 시작한 팀이 하나 있다고 해서 그쪽으로 승리가 넘어가면 안 된다.
 *
 * <h2>순수 판정과 월드를 보는 부분을 갈라 뒀다</h2>
 * <p>{@link #resolve(TeamManager, Candidates)} 는 살아 있는 서버 없이 도는 <b>순수 판정</b>
 * 이고, 위의 결정이 전부 그 안에 있다. 엔티티에서 후보를 뽑는 일만
 * {@link #candidatesOf} 가 한다. {@code GameOverCountdown} 이나
 * {@code NoAttackDamageLossEffect.reduces} 와 같은 모양이다.
 */
public final class VictoryTeamResolver {
	/** 팀을 못 찾았을 때 로그에 적을 자리 표시. 줄이 {@code null} 로 깨지지 않게 한다. */
	private static final String UNKNOWN_TEAM_NAME = "?";

	private VictoryTeamResolver() {
	}

	/** 승리 팀을 <b>어느 단계에서</b> 찾았는가. 간접 처치를 로그만 보고 알아볼 수 있게 남긴다. */
	public enum Outcome {
		/** {@code source.getEntity()} 가 사람이었다. */
		CAUSING_PLAYER,
		/** 직접 원인(TNT·투사체)의 간접 소유자가 사람이었다. */
		INDIRECT_OWNER,
		/** 드래곤이 기억하는 마지막 가해자가 있었다. */
		LAST_HURT_BY_PLAYER,
		/** 아무도 못 찾았지만 회차를 시작한 팀이 하나뿐이었다. */
		SOLE_STARTED_TEAM,
		/** 찾아낸 팀이 아직 회차를 시작하지 않았다. <b>승리로 세지 않는다.</b> */
		NOT_STARTED,
		/** 처치자도 팀도 못 찾았다. 회차는 끝나되 「모험가」로 남는다. */
		UNKNOWN
	}

	/**
	 * 처치자 후보 셋. 순수 판정이 보는 전부다.
	 *
	 * <p>엔티티가 아니라 {@code UUID} 로 들고 다닌다. 판정에 필요한 것은 「그 사람의 팀」뿐이고
	 * {@code TeamManager} 는 {@code UUID} 로 찾으므로, 엔티티를 그대로 넘기면 살아 있는 월드
	 * 없이는 시험할 수 없는 판정이 된다.
	 *
	 * @param causingPlayer    {@code DamageSource.getEntity()} 가 사람이면 그 사람
	 * @param indirectOwner    {@code getDirectEntity()} 의 간접 소유자가 사람이면 그 사람
	 * @param lastHurtByPlayer 드래곤이 기억하는 마지막 가해자
	 */
	public record Candidates(@Nullable UUID causingPlayer, @Nullable UUID indirectOwner,
			@Nullable UUID lastHurtByPlayer) {
		/** 아무도 못 찾은 경우. 주인 없는 폭발이 이 모양이다. */
		public static final Candidates NONE = new Candidates(null, null, null);
	}

	/**
	 * 판정 결과.
	 *
	 * @param team    승리 팀. 못 찾았으면 {@code null}
	 * @param killer  처치자. 4단계로 고른 팀에는 처치자가 없으므로 {@code null} 일 수 있다
	 * @param outcome 어느 단계에서 어떻게 끝났는가
	 */
	public record Resolution(@Nullable ShareTeam team, @Nullable UUID killer, Outcome outcome) {
		/** 처치자도 팀도 못 찾았다. */
		public static final Resolution UNKNOWN = new Resolution(null, null, Outcome.UNKNOWN);

		/**
		 * 이 죽음을 <b>회차 승리로 세는가.</b>
		 *
		 * <p>거짓인 경우는 하나뿐이다 — 찾아낸 팀이 아직 회차를 시작하지 않았을 때. 팀을 못
		 * 찾은 것({@link Outcome#UNKNOWN})은 승리가 아닌 것과 다르다. 그때도 회차는 끝나고,
		 * 다만 승리 팀 없이 「모험가」로 적힌다 — 예전과 같은 동작이다.
		 */
		public boolean victory() {
			return outcome != Outcome.NOT_STARTED;
		}

		/** 로그에 적을 팀 이름. 팀을 못 찾았으면 자리 표시만 남긴다. */
		public String teamName() {
			return team == null ? UNKNOWN_TEAM_NAME : team.name();
		}
	}

	// ------------------------------------------------------------------ 순수 판정

	/**
	 * 후보에서 승리 팀을 고른다. <b>살아 있는 서버 없이 도는 순수 판정이다.</b>
	 *
	 * <p>클래스 문서의 네 단계가 그대로 여기 있다. {@code manager} 가 {@code null} 이면 팀을
	 * 찾을 길이 없으므로 처치자만 돌려준다.
	 */
	public static Resolution resolve(@Nullable TeamManager manager, Candidates candidates) {
		Resolution killer = byKiller(manager, candidates);
		if (killer != null) {
			return killer;
		}
		// 여기부터가 예전에 그냥 포기하던 자리다. 처치자를 아무 데서도 못 찾았다.
		ShareTeam sole = soleStartedTeam(manager);
		return sole == null
				? Resolution.UNKNOWN
				: new Resolution(sole, null, Outcome.SOLE_STARTED_TEAM);
	}

	/**
	 * 1~3단계. 사람을 찾으면 그 자리에서 끝난다 — <b>그 사람이 팀이 없어도 끝난다.</b>
	 *
	 * @return 사람을 찾았으면 그 결과, 하나도 못 찾았으면 {@code null}
	 */
	private static @Nullable Resolution byKiller(
			@Nullable TeamManager manager, Candidates candidates) {
		Resolution causing = forKiller(manager, candidates.causingPlayer(),
				Outcome.CAUSING_PLAYER);
		if (causing != null) {
			return causing;
		}
		Resolution indirect = forKiller(manager, candidates.indirectOwner(),
				Outcome.INDIRECT_OWNER);
		if (indirect != null) {
			return indirect;
		}
		return forKiller(manager, candidates.lastHurtByPlayer(), Outcome.LAST_HURT_BY_PLAYER);
	}

	/** 후보 하나를 결과로 만든다. 후보가 없으면 {@code null} 이라 다음 단계로 넘어간다. */
	private static @Nullable Resolution forKiller(
			@Nullable TeamManager manager, @Nullable UUID killer, Outcome found) {
		if (killer == null) {
			return null;
		}
		if (manager == null) {
			return new Resolution(null, killer, found);
		}
		ShareTeam team = manager.teamOf(killer);
		if (team != null && !startedTeam(manager.stateByTeamId(team.teamId()))) {
			// 시작하지 않은 팀이 드래곤을 잡아도 회차 승리가 아니다. 시작 전에는 회차 자체가
			// 없고, 여기서 승리로 세면 아무도 시작하지 않은 회차가 끝나 버린다. 다음 단계로
			// 넘기지도 않는다 — 넘기면 남이 잡은 드래곤으로 이기는 길이 열린다.
			return new Resolution(team, killer, Outcome.NOT_STARTED);
		}
		return new Resolution(team, killer, found);
	}

	/**
	 * 회차를 시작한 팀이 정확히 하나면 그 팀, 아니면 {@code null}.
	 *
	 * <p>둘 이상이면 <b>찍지 않는다.</b> 한 대도 때리지 않은 팀에게 승리 책을 쥐여 주는 것보다
	 * 승리 팀 없이 끝나는 편이 낫다. 하나도 없으면 애초에 끝날 회차가 없다.
	 *
	 * <p>{@code GameStartManager.anyTeamStarted} 와 같은 것을 세지만 여기서는 <b>몇 개인지</b>가
	 * 필요해 따로 돈다.
	 */
	public static @Nullable ShareTeam soleStartedTeam(@Nullable TeamManager manager) {
		if (manager == null) {
			return null;
		}
		ShareTeam found = null;
		for (ShareTeam team : manager.allTeams()) {
			if (!startedTeam(manager.stateByTeamId(team.teamId()))) {
				continue;
			}
			if (found != null) {
				return null;
			}
			found = team;
		}
		return found;
	}

	/**
	 * 이 팀 상태가 <b>회차를 시작한</b> 상태인가.
	 *
	 * <p>{@code GameStartManager.started} 를 쓰되 {@code null} 을 먼저 막는다. 그쪽은 팀이
	 * 없으면 참이다 — 「막을 것이 없다」는 뜻이라 그 자리에서는 맞지만, 여기서는 <b>팀을 세고
	 * 있으므로</b> 상태를 모르는 팀을 「시작했다」로 세면 안 된다.
	 * {@code GameStartManager.anyTeamStarted} 와 같은 모양이다.
	 *
	 * <p>{@code preStart} 는 이 물음이 아니다. 그쪽은 「시작 전 보호를 받는가」이고 팀이 없어도
	 * 참이다. 두 물음을 혼동해 사고가 난 적이 있어 {@code GameStartManager} 에 길게 적혀 있다.
	 */
	public static boolean startedTeam(@Nullable TeamState state) {
		return state != null && GameStartManager.started(state);
	}

	// ------------------------------------------------------------------ 월드에서 후보 뽑기

	/**
	 * 죽음 하나에서 승리 팀을 찾는다. {@code RunProgressManager.onDeath} 가 부르는 자리.
	 */
	public static Resolution resolve(@Nullable MinecraftServer server, EnderDragon dragon,
			@Nullable DamageSource source) {
		TeamManager manager = server == null ? null : TeamManager.get(server);
		return resolve(manager, candidatesOf(dragon, source));
	}

	/**
	 * 드래곤과 피해 원인에서 처치자 후보 셋을 뽑는다. <b>고르지는 않는다</b> — 고르는 일은
	 * {@link #resolve(TeamManager, Candidates)} 가 한다.
	 *
	 * <p>2단계에 바닐라 {@code Explosion.getIndirectSourceEntity(Entity)} 를 그대로 쓴다.
	 * 26.3 에서 {@code public static LivingEntity getIndirectSourceEntity(Entity)} 이고
	 * ({@code net.minecraft.world.level.Explosion} 의 인터페이스 정적 메서드),
	 * {@code PrimedTnt} 는 소유자를, {@code Projectile} 은 쏜 사람을 풀며,
	 * {@code LivingEntity} 는 자기 자신을, 나머지는 {@code null} 을 돌려준다. <b>인자가
	 * {@code null} 이어도 {@code null} 을 돌려준다</b>(타입 스위치가 {@code -1} 로 떨어진다).
	 * 직접 같은 판정을 베껴 쓰지 않는 이유는, 바닐라가 폭발 피해 원인을 만들 때 쓰는 바로 그
	 * 함수라 뜻이 어긋날 수 없기 때문이다.
	 */
	public static Candidates candidatesOf(EnderDragon dragon, @Nullable DamageSource source) {
		if (source == null) {
			return new Candidates(null, null, playerId(dragon.getLastHurtByPlayer()));
		}
		return new Candidates(
				playerId(source.getEntity()),
				playerId(Explosion.getIndirectSourceEntity(source.getDirectEntity())),
				playerId(dragon.getLastHurtByPlayer()));
	}

	/** 이 엔티티가 사람이면 그 {@code UUID}. 아니면 {@code null}. */
	private static @Nullable UUID playerId(@Nullable Entity entity) {
		return entity instanceof Player player ? player.getUUID() : null;
	}

	/**
	 * 찾아낸 처치자를 접속 중인 사람으로 되살린다. 승리 문구에 쓸 이름을 얻는 데만 쓴다.
	 *
	 * <p>4단계로 고른 팀에는 처치자가 없고, 처치자가 있어도 같은 틱에 접속이 끊겼으면
	 * {@code null} 이다. 어느 쪽이든 부르는 쪽이 팀 이름이나 「모험가」로 물러선다.
	 */
	public static @Nullable ServerPlayer killerPlayer(
			@Nullable MinecraftServer server, @Nullable Resolution resolution) {
		if (server == null || resolution == null || resolution.killer() == null) {
			return null;
		}
		return server.getPlayerList().getPlayer(resolution.killer());
	}
}

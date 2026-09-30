package com.sharedfate.perk;

import com.mojang.datafixers.util.Pair;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.RuinSurveyEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 유적 좌표 효과({@code ruin_survey})의 집행부. 증강 「유적 감별사」가 여기로 들어온다.
 *
 * <p>{@link RuinSurveyEffect} 가 「무엇을 어디서 얼마나 넓게」만 들고, 실제로 찾아 기억하고
 * 글줄로 만드는 일은 전부 여기서 한다.
 *
 * <h2>언제 찾는가 — <b>고르는 순간 한 번</b></h2>
 * <p>구조물 탐색은 서버 스레드를 붙잡는 동기 작업이다({@link PerkCompassTargets} 의 「탐색은
 * 비싸다」를 보라). 그래서 한 번만 찾고 그 답을 그대로 들고 간다. 고르는 순간을 택한 까닭은
 * 셋이다.
 *
 * <ol>
 *   <li><b>그때 이미 서버가 멈춰 있다.</b> 증강을 고르는 그 틱에는 이미 화면이 떠 있고 지급
 *       연쇄가 돌고 있어, 한 번의 탐색이 끼어들 자리로 가장 눈에 안 띈다.</li>
 *   <li><b>답이 흔들리지 않는다.</b> 「차원에 들어갈 때마다」로 하면 사람이 움직인 만큼 가장
 *       가까운 유적이 바뀌어, 채팅에 적어 준 좌표와 목록에 뜨는 좌표가 서로 달라진다. 한 번
 *       정한 목적지가 그대로 남아야 「저기로 가자」가 성립한다.</li>
 *   <li><b>채팅 한 줄을 그 자리에서 띄울 수 있다.</b> 사람이 요청한 「맨 처음 골랐을 때
 *       채팅창에도 한 번」이 곧 이 시점이다.</li>
 * </ol>
 *
 * <h2>어디서부터 찾는가</h2>
 * <p>고른 사람이 그 차원에 서 있으면 그 자리에서, 아니면 그 차원의 <b>공용 스폰</b>에서 찾는다.
 * 네더에 선 채로 고르면 좌표가 8분의 1로 접혀 있어 그대로 쓰면 엉뚱한 데를 훑게 된다. 스폰은
 * 누구에게나 뜻이 통하는 기준점이고, 오버월드로 나오면 대개 그 근처다.
 *
 * <h2>못 찾으면</h2>
 * <p>{@link #NOT_FOUND} 를 보여 준다. 반경 안에 없거나(먼저 생긴 세계의 바깥), 그 구조물이
 * 이 판에 없거나(데이터팩으로 껐거나), 차원 자체가 없을 때 전부 같은 줄이다. 셋을 갈라 보여
 * 줘 봐야 사람이 할 수 있는 일은 똑같다 — 더 멀리 나가 보는 것뿐이다.
 *
 * <h2>기억은 메모리에만 둔다</h2>
 * <p>저장하지 않는다. 서버를 껐다 켜면 비고, 그때는 {@link #ensure} 가 접속해 있는 팀원을
 * 기준으로 다시 한 번 찾는다. 좌표가 달라질 수 있지만, 저장 형식을 늘리는 값어치는 없다 —
 * 어차피 회차가 끝나면 버릴 값이다.
 */
public final class PerkRuinSurvey {
	/** 채팅 앞에 붙이는 말. 다른 증강들과 같은 것을 쓴다. */
	public static final String PREFIX = "[증강] ";

	/** 찾지 못했을 때 좌표 자리에 넣는 말. */
	public static final String NOT_FOUND = "찾지 못함";

	/** 팀마다 한 벌. 정의가 바뀌면 통째로 버린다. */
	private static final Map<UUID, Survey> SURVEYS = new HashMap<>();

	/** 같은 경고로 로그를 채우지 않기 위한 표시. */
	private static volatile boolean warned;

	private PerkRuinSurvey() {
	}

	/**
	 * 찾아 둔 유적 하나.
	 *
	 * @param label 정의에 적힌 이름표
	 * @param pos   찾은 자리. 못 찾았으면 {@code null}
	 */
	public record Found(String label, @Nullable BlockPos pos) {
		/** 「고대 도시  -1234, 567」처럼 한 줄로 적는다. y 는 넣지 않는다 — 파고 들어갈 자리다. */
		public String line() {
			return pos == null ? label + "  " + NOT_FOUND : label + "  " + pos.getX() + ", "
					+ pos.getZ();
		}
	}

	/** 한 팀의 조사 결과. 어느 정의로 찾았는지를 함께 들어 정의가 바뀌면 알아챈다. */
	private record Survey(RuinSurveyEffect effect, List<Found> found) {
	}

	/** 서버가 멈출 때나 시험에서 상태를 격리할 때 쓴다. */
	public static synchronized void reset() {
		SURVEYS.clear();
		warned = false;
	}

	// ------------------------------------------------------------------ 조회

	/**
	 * 이 팀이 가진 유적 좌표 효과. 없으면 {@code null}.
	 *
	 * <p>보유 증강과 켜진 세트 단계를 둘 다 훑는다. 지금은 증강 쪽에만 있다.
	 */
	public static @Nullable RuinSurveyEffect effectOf(@Nullable TeamState state) {
		TeamState active = PerkWorldRules.activeState(state);
		if (active == null) {
			return null;
		}
		for (String perkId : active.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof RuinSurveyEffect survey) {
					return survey;
				}
			}
		}
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(active)) {
			if (effect instanceof RuinSurveyEffect survey) {
				return survey;
			}
		}
		return null;
	}

	/**
	 * 이 팀에게 보여 줄 좌표 줄들. 효과가 없으면 빈 목록.
	 *
	 * <p>아직 찾아 두지 않았으면 <b>여기서 찾지 않는다.</b> 화면을 그리는 길에서 구조물 탐색이
	 * 벌어지면 창을 열 때마다 서버가 멎는다. 찾아 두는 일은 {@link #ensure} 가 맡고, 그것은
	 * 증강을 고를 때와 사람이 접속할 때만 불린다.
	 */
	public static synchronized List<String> lines(@Nullable ShareTeam team,
			@Nullable TeamState state) {
		RuinSurveyEffect effect = effectOf(state);
		if (team == null || effect == null) {
			return List.of();
		}
		Survey survey = SURVEYS.get(team.teamId());
		if (survey == null || survey.effect() != effect) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(survey.found().size());
		for (Found found : survey.found()) {
			lines.add(found.line());
		}
		return lines;
	}

	/** 해체된 팀의 기억을 턴다. */
	public static synchronized void retainAll(Set<UUID> living) {
		SURVEYS.keySet().retainAll(living);
	}

	// ------------------------------------------------------------------ 찾기

	/**
	 * 증강을 고른 그 자리에서 한 번 찾고, 팀 전원에게 채팅 한 줄을 띄운다.
	 *
	 * <p>{@link PerkGrantChain} 이 다른 즉시 지급들과 같은 자리에서 부른다. 이 증강이 아니면
	 * 아무 일도 하지 않는다.
	 *
	 * @return 실제로 찾아 알렸으면 참
	 */
	public static boolean surveyOnChoice(@Nullable MinecraftServer server,
			@Nullable ShareTeam team, @Nullable TeamState state, @Nullable Perk chosen) {
		if (server == null || team == null || state == null || chosen == null) {
			return false;
		}
		RuinSurveyEffect effect = null;
		for (PerkEffect candidate : chosen.effects()) {
			if (candidate instanceof RuinSurveyEffect survey) {
				effect = survey;
				break;
			}
		}
		if (effect == null) {
			return false;
		}
		List<Found> found = survey(server, team, effect);
		if (found.isEmpty()) {
			return false;
		}
		announce(server, team, chosen.name(), found);
		return true;
	}

	/**
	 * 아직 찾아 두지 않았으면 지금 찾는다. 이미 있으면 아무 일도 하지 않는다.
	 *
	 * <p>서버를 다시 켠 뒤를 위한 자리다. 기억을 저장하지 않으므로 그때 한 번 다시 찾아야
	 * 목록에 좌표가 뜬다. 채팅은 띄우지 않는다 — 처음 고를 때 이미 한 번 알렸다.
	 */
	public static void ensure(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable TeamState state) {
		if (server == null || team == null) {
			return;
		}
		RuinSurveyEffect effect = effectOf(state);
		if (effect == null) {
			return;
		}
		synchronized (PerkRuinSurvey.class) {
			Survey existing = SURVEYS.get(team.teamId());
			if (existing != null && existing.effect() == effect) {
				return;
			}
		}
		survey(server, team, effect);
	}

	/** 실제로 찾아 기억에 넣는다. 찾은 것들을 돌려준다. */
	private static List<Found> survey(MinecraftServer server, ShareTeam team,
			RuinSurveyEffect effect) {
		try {
			ServerLevel level = server.getLevel(effect.dimension());
			if (level == null) {
				SharedFateMod.LOGGER.warn("ruin_survey 가 가리키는 차원이 없습니다: {}",
						effect.dimension().identifier());
				return List.of();
			}
			BlockPos origin = origin(server, team, level);
			List<Found> found = new ArrayList<>(effect.targets().size());
			for (RuinSurveyEffect.Target target : effect.targets()) {
				found.add(new Found(target.label(),
						search(server, level, origin, effect.searchRadius(), target)));
			}
			List<Found> frozen = List.copyOf(found);
			synchronized (PerkRuinSurvey.class) {
				SURVEYS.put(team.teamId(), new Survey(effect, frozen));
			}
			return frozen;
		} catch (RuntimeException error) {
			warnOnce(error);
			return List.of();
		}
	}

	/**
	 * 탐색 기준점.
	 *
	 * <p>그 차원에 서 있는 팀원이 있으면 그 사람 자리, 없으면 세계의 스폰 자리다. 차원이
	 * 다른 사람의 좌표를 그대로 쓰면 네더 ↔ 오버월드의 8배 차이 때문에 엉뚱한 데를 훑는다.
	 *
	 * <p>26.3 에는 {@code getSharedSpawnPos()} 가 없다. 스폰은
	 * {@code Level.getRespawnData()} 가 들고 있는 {@code LevelData.RespawnData} 로 옮겨졌고,
	 * 그 안에 차원과 자리가 함께 들어 있다. 그 차원이 우리가 찾을 차원과 다르면 쓸 수 없으므로
	 * 원점(0, 0)에서 찾는다 — 오버월드가 아닌 차원을 정의에 적었을 때만 닿는 길이다.
	 */
	private static BlockPos origin(MinecraftServer server, ShareTeam team, ServerLevel level) {
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null && online.level().dimension().equals(level.dimension())) {
				return online.blockPosition();
			}
		}
		var respawn = level.getRespawnData();
		if (respawn != null && level.dimension().equals(respawn.dimension())) {
			return respawn.pos();
		}
		return BlockPos.ZERO;
	}

	/**
	 * 구조물 하나를 찾는다. 반경 안에 없으면 {@code null}.
	 *
	 * <p>{@link PerkCompassTargets} 가 쓰는 것과 <b>같은 호출</b>이다. 26.3 의
	 * {@code ServerLevel.findNearestMapStructure} 는 태그만 받으므로, 이름 하나짜리 정의를
	 * {@code HolderSet} 으로 감싸 {@code ChunkGenerator.findNearestMapStructure} 를 직접 부른다.
	 * 마지막 인자는 「이미 만들어진 구조물을 건너뛸지」이고 바닐라 {@code /locate structure} 와
	 * 같이 거짓을 준다.
	 */
	private static @Nullable BlockPos search(MinecraftServer server, ServerLevel level,
			BlockPos origin, int radius, RuinSurveyEffect.Target target) {
		HolderSet<Structure> structures = target.resolve(server.registryAccess());
		if (structures == null || structures.size() == 0) {
			SharedFateMod.LOGGER.warn("ruin_survey 가 가리키는 구조물을 찾을 수 없습니다: {}",
					target.key().identifier());
			return null;
		}
		Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
				.findNearestMapStructure(level, structures, origin, radius, false);
		return found == null ? null : found.getFirst();
	}

	// ------------------------------------------------------------------ 알림

	/** 팀 전원에게 좌표를 한 번 알린다. 자막이 아니라 채팅이다 — 나중에 다시 읽을 수 있어야 한다. */
	private static void announce(MinecraftServer server, ShareTeam team, String perkName,
			List<Found> found) {
		StringBuilder text = new StringBuilder(PREFIX).append(perkName).append(": ");
		for (int index = 0; index < found.size(); index++) {
			if (index > 0) {
				text.append("  ·  ");
			}
			text.append(found.get(index).line());
		}
		Component message = Component.literal(text.toString()).withStyle(ChatFormatting.AQUA);
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				online.sendSystemMessage(message);
			}
		}
	}

	/** 접속한 사람의 팀을 한 번 챙긴다. 서버를 다시 켠 뒤 좌표가 비어 있는 것을 메운다. */
	public static void onPlayerJoin(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		if (team == null) {
			return;
		}
		ensure(server, team, manager.stateByTeamId(team.teamId()));
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn("유적 좌표를 찾지 못했습니다. 이 경고는 한 번만 남습니다.", error);
	}
}

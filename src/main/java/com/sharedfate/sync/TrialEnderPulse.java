package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.EnderPulse} 실행기 — 엔더 파동.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p>20초({@code interval})마다 중앙에서 바닥 고리가 하나 출발해 4초({@code travelTicks})에 걸쳐
 * 42칸({@code maxRadius})까지 퍼진다. 고리가 지나갈 때
 *
 * <ul>
 *   <li><b>바닥을 딛고 있으면</b> 구속 III 가 3초({@code rootTicks}) 붙는다</li>
 *   <li><b>점프해 있으면 통과</b>한다 — 이 카드가 요구하는 행동이 그것 하나다</li>
 * </ul>
 *
 * <p><b>피해는 없다.</b> 이 카드가 빼앗는 것은 체력이 아니라 발이다.
 *
 * <h2>「점프해 있다」를 한 틱으로 묻지 않는다</h2>
 *
 * <p>고리가 어떤 사람의 자리를 지나가는 것은 <b>한 틱</b>이다. 그 한 틱에 {@code onGround()} 만
 * 물으면 0.05초 차이로 억울하게 걸린다 — 사람은 눈으로 본 것에 반응해 스페이스를 누르고, 그
 * 입력이 서버의 {@code onGround} 를 뒤집기까지 또 한 왕복이 걸린다.
 *
 * <p>그래서 고리에 <b>두께</b>를 준다. 앞머리가 내 자리를 지난 뒤 {@link #JUMP_WINDOW_TICKS}
 * 틱 동안이 「고리가 내 위에 있는 시간」이고, <b>그 사이 한 틱이라도 공중에 있었으면 통과</b>다.
 * 판정은 그 창이 닫히는 틱, 즉 고리의 <b>뒷자락</b>이 나를 지나는 틱에 내린다.
 *
 * <p>창이 <b>뒤로만</b> 열려 있는 것은 일부러다. 늦게 뛰는 것은 이 창이 봐 주지만 너무 일찍 뛴
 * 것은 봐 주지 않는데, 바닐라 점프는 그 자체로 열두 틱쯤 공중에 떠 있어 <b>앞쪽 여유는 점프가
 * 이미 들고 있기</b> 때문이다. 이 창이 메우는 것은 「보고 나서 누르기까지」 한쪽뿐이다.
 *
 * <h2>구속은 팀 전체에 걸린다 — 의도한 것이다</h2>
 *
 * <p>{@code shareStatusEffects} 가 켜져 있어 {@link EffectSync} 가 한 사람에게 붙은 상태이상을
 * 팀원 전원에게 그대로 옮긴다. 즉 <b>한 명이 못 뛰면 넷이 다 묶인다.</b> 사람이 「의도한 거임」
 * 이라고 확인한 동작이므로 <b>되돌리지 말 것.</b>
 *
 * <p>고리는 사람마다 <b>다른 틱에</b> 닿는다(중앙에서 먼 사람일수록 늦다). 그래서 먼저 걸린
 * 사람 때문에 아직 고리가 오지 않은 사람도 미리 묶인다. 그것이 이 카드가 공유 체력 게임에서
 * 갖는 긴장이다 — 각자 제 순간에 뛰어야 하고, 한 사람의 실패가 팀 전체의 실패다.
 *
 * <h2>왜 규약 색을 쓰지 않고 엔더 입자를 쓰는가</h2>
 *
 * <p>{@link TrialWarning.Colors} 의 넷(빨강=서 있으면 죽는다, 노랑=번개, 파랑=밀려난다,
 * 보라=너 하나를 노린다)은 어느 것도 이 카드의 뜻이 아니다. 여기에 다섯째 색을 더하면 그 순간
 * 규약이 규약이 아니게 되므로({@code Colors} 의 설명) <b>먼지 표식 자체를 쓰지 않는다.</b>
 * 규약은 먼지 고리의 규약이고, 엔더 입자는 그 바깥이라 색을 하나도 빌려 쓰지 않는다.
 *
 * <p>고른 것은 두 가지다. <b>{@code CRIT} 이 앞머리, {@code PORTAL} 이 몸통</b>이다. 26.3 의
 * 클라이언트 입자를 뜯어 보고 갈랐다.
 *
 * <ul>
 *   <li>{@code PortalParticle} — 수명 <b>40~49틱</b>이고 {@code getQuadSize} 가 나이에 따라
 *       <b>0 에서 자란다.</b> 갓 찍은 점이 가장 작다는 뜻이라, 이것만으로 그리면 <b>앞머리가 가장
 *       흐리다.</b> 대신 남아서 커지므로 <b>지나간 자리</b>를 보라색으로 채운다 — 고리 안쪽은 이미
 *       지나간 땅이라 「지금 어디가 위험한가」를 흐리지 않고, 파동이 어디서 와서 어디로 가는지를
 *       알려 준다</li>
 *   <li>{@code CritParticle} — 수명이 {@code max(1, 6.0 / (굴림×0.8 + 0.6))} 이라 <b>4~10틱</b>
 *       이다. 카드 값에서 고리 속도가 틱당 {@code 42/80 = 0.525칸}이므로 자국이 2~5칸에서 끝나
 *       <b>앞머리가 선으로 남는다.</b> 흰 불티라 규약의 네 색 어디에도 닿지 않는다</li>
 * </ul>
 *
 * <p>파랑 계열({@code ENCHANTED_HIT})을 쓰지 않은 이유를 남긴다. 대비는 그쪽이 낫지만
 * <b>파랑은 「밀려난다」로 이미 배워져 있고 이 카드에는 넉백이 없다.</b> 먼지가 아니니 규약 위반은
 * 아니어도, 색이 거짓말을 하면 규약을 지킨 보람이 없다.
 *
 * <h2>넉백을 주지 않는다</h2>
 *
 * <p>엔드 중앙 섬은 사방이 허공이고 체력이 팀 공유라 <b>한 사람의 낙사가 팀 전체를 끝낸다.</b>
 * 이 카드는 아무것도 밀지 않는다 — 미는 코드를 한 줄도 두지 않는 것이 그 장치다.
 */
public final class TrialEnderPulse {

	// ------------------------------------------------------------------ 값

	/**
	 * 앞머리가 지나간 뒤 「뛰었다」로 쳐 주는 시간(틱). 고리 두께이기도 하다.
	 *
	 * <p>{@link TrialWarning#TICKS_SIDESTEP} 의 설명이 「사람의 지각·판단·입력에만 0.25초가
	 * 든다」고 적어 둔 그 0.25초다. 이 카드가 요구하는 것은 <b>옆걸음이 아니라 점프 한 번</b>이라
	 * 고를 자리도 갈 거리도 없다 — 남는 것이 딱 그 반응 시간뿐이라 여기를 창의 길이로 삼는다.
	 *
	 * <p>여기를 0 으로 만들면 판정이 다시 한 틱짜리가 되고, 사람이 눈으로 맞춘 점프가 서버에
	 * 닿기 전에 묶인다. 키우면 고리가 두꺼워져 「고리를 보고 나서」 뛰어도 통과하게 된다.
	 */
	static final int JUMP_WINDOW_TICKS = 5;

	/**
	 * 구속 III 의 증폭값. 레벨 I 이 0 이므로 III 은 2 다.
	 *
	 * <p>카드에 적힌 「구속 III」을 코드로 옮긴 것뿐이다. 세기를 바꾸려면 카드 값을 늘려
	 * {@code TrialCatalog} 에서 받아야 한다 — 여기를 올리면 카드 설명과 실제가 갈라진다.
	 */
	static final int ROOT_AMPLIFIER = 2;

	/**
	 * 앞머리 고리 한 바퀴에 찍는 점 수의 상한.
	 *
	 * <p>이 카드가 <b>반드시 읽혀야 하는 한 줄</b>이라 예산의 큰 쪽을 준다. 반경 42 에서
	 * 둘레가 264칸이므로 점 사이가 {@code 264 / 240 = 1.1칸}이다 — {@link TrialWarning#ringPoints}
	 * 가 상한 80 에 걸려 같은 반경에서 3.3칸까지 벌어지는 것과 견주면 세 배 촘촘하다.
	 * {@link #edgeGap} 으로 그 값을 직접 물을 수 있다.
	 */
	static final int EDGE_MAX_POINTS = 240;
	/**
	 * 몸통 고리 한 바퀴에 찍는 점 수의 상한.
	 *
	 * <p>이쪽은 <b>남아서 쌓인다.</b> 수명이 40~49틱이라 화면에 살아 있는 수는
	 * {@code 이 값 × 수명}이고, 앞머리와 같은 240 을 주면 만 점을 넘긴다. 몸통은 경계를 말하는
	 * 줄이 아니라 지나간 자리를 채우는 안개라 성겨도 제 몫을 한다.
	 */
	static final int WAKE_MAX_POINTS = 160;
	/**
	 * 한 틱에 이 카드가 쓰는 점 수.
	 *
	 * <p>숫자를 따로 박지 않고 위 둘에서 뽑는다 — 한쪽만 고치면 예산이 조용히 깨진다. 400 은
	 * 이 저장소가 이미 쓰는 예산이다({@code DragonFireBarrage.MARK_MAX_POINTS} 의 설명 —
	 * 「낙뢰」가 반경 3 짜리 고리 열 개로 정확히 400점을 쓴다).
	 */
	static final int MAX_POINTS_PER_TICK = EDGE_MAX_POINTS + WAKE_MAX_POINTS;

	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다({@code TrialWarning} 과 같은 이유). */
	private static final double GROUND_OFFSET = 0.15;
	/**
	 * 하이트맵이 허공을 돌려줬을 때 쓰는 높이.
	 *
	 * <p>{@code DragonFireBarrage.FALLBACK_GROUND_Y} 와 같은 값이고 까닭도 같다 — 중앙 섬
	 * 표면이다.
	 */
	private static final double FALLBACK_GROUND_Y = 63.0;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 퍼지고 있는 고리들.
	 *
	 * <p><b>열쇠에 주의.</b> {@link TrialFireball} 과 같이 「받은 틱 + 위험 값」으로 만든 내부
	 * 열쇠다. 이 위험을 가진 카드가 하나뿐이라 충분하지만, 값이 완전히 같은 파동 둘을 한 카드에
	 * 걸면 두 고리가 하나로 합쳐진다.
	 *
	 * <p>✅ <b>진입점이 이제 {@code key} 를 받는다.</b> 그 구멍을 막으려면 여기를 그 열쇠로
	 * 바꾸면 된다 — {@link TrialFireball} 에 같은 글을 남겨 두었다.
	 */
	private static final Map<Key, Pulse> PULSES = new HashMap<>();
	/**
	 * 사람마다 <b>마지막으로 공중에 있던 틱.</b>
	 *
	 * <p>고리가 없는 동안에도 계속 적는다. 판정 틱에만 보면 그 한 틱의 {@code onGround()} 를
	 * 묻는 것과 같아져 창이 아무 일도 하지 않는다.
	 *
	 * <p>정적 맵인 이유는 위험이 값(레코드)이라 상태를 들 수 없기 때문이다. 월드가 바뀌면 지난
	 * 판의 기록이 새 판의 첫 고리를 통과시키므로 {@link #clearState()} 로 반드시 비운다.
	 */
	private static final Map<UUID, Long> LAST_AIRBORNE = new HashMap<>();

	private record Key(long granted, TrialCatalog.Risk.EnderPulse risk) {
	}

	/**
	 * 퍼지고 있는 고리 하나.
	 *
	 * @param index   몇 번째 파동인가. 주기가 넘어갔는지 판단한다
	 * @param groundY 고리를 얹을 높이. 고리가 출발할 때 중앙에서 한 번만 잰다 — 까닭은 그 값을
	 *                재는 곳에 적어 두었다
	 * @param crossed 이미 고리가 지나간 사람들. <b>이 집합은 고쳐 쓴다.</b> 고리가 한 사람을 두
	 *                번 지나가지 않게 막는 것이 전부인데, 안 막으면 고리 끝에 붙어 바깥으로 달리는
	 *                사람이 같은 파동에 두 번 묶인다
	 */
	private record Pulse(long index, double groundY, Set<UUID> crossed) {
	}

	private TrialEnderPulse() {
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자({@code dragon})도 받는 것은
	 * {@link TrialRisks} 의 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <p>{@code now} 를 받아 쓰고 {@code level.getGameTime()} 을 부르지 않는다. 룰렛이 도는
	 * 동안 {@code ServerTickRateManager} 가 판을 멈추면 게임 시각도 멈추는데, 그때 직접 물으면
	 * 고리가 얼어붙은 채로 남는다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱. 주기는 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.EnderPulse risk) {
		if (end == null || members == null || members.isEmpty() || risk == null) {
			return;
		}
		int interval = risk.interval();
		int travelTicks = risk.travelTicks();
		if (interval <= 0 || travelTicks <= 0 || !(risk.maxRadius() > 0.0)) {
			return;
		}

		// 고리가 없는 동안에도 적는다. 판정 창이 과거를 보므로 기록이 끊기면 창이 비어 버린다.
		recordAirborne(members, now);

		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		// 받은 직후 한 주기는 온전히 비워 둔다 — 카드 설명을 읽는 중에 묶이면 안 된다.
		if (elapsed < interval) {
			return;
		}
		// 위상은 월드 시간이 아니라 받은 틱에서 센다. now % interval 로 세면 주기가 같은 카드가
		// 전부 같은 틱에 몰려 터진다.
		int step = (int) (elapsed % interval);
		Key pulseKey = new Key(granted, risk);
		if (step > lifetime(interval, travelTicks)) {
			// 뒷자락까지 다 지나갔다. 들고 있으면 다음 파동이 지난 파동의 명단을 물려받는다.
			PULSES.remove(pulseKey);
			return;
		}

		long index = elapsed / interval;
		Pulse pulse = PULSES.get(pulseKey);
		if (pulse == null || pulse.index() != index) {
			pulse = new Pulse(index, groundY(end), new HashSet<>());
			PULSES.put(pulseKey, pulse);
		}

		if (step <= travelTicks) {
			draw(end, pulse.groundY(), radiusAt(step, travelTicks, risk.maxRadius()));
		}
		warn(end, members, step, risk);
		judge(end, members, pulse, step, now, risk);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. 사람에게 붙은 것이 다음 판으로 새지 않게 한다.
	 *
	 * <p>거는 구속은 지속시간이 있어 저절로 풀리므로 걷어낼 것이 없다. 비워야 하는 것은 <b>지난
	 * 판의 고리와 발 기록</b>이다 — 남겨 두면 새 판의 첫 고리가 옛 명단을 보고 사람을 건너뛰거나,
	 * 지난 판에 뛰어 있던 기록이 첫 고리를 그냥 통과시킨다. 컴파일도 시험도 조용한 사고다.
	 */
	public static void clearState() {
		PULSES.clear();
		LAST_AIRBORNE.clear();
	}

	// ------------------------------------------------------------------ 발 기록

	/**
	 * 지금 공중에 있는 사람을 적어 둔다.
	 *
	 * <p>접속을 끊은 사람의 기록은 버린다. 들고 있어 봐야 다시 들어왔을 때 <b>몇 분 전에 뛴 것</b>
	 * 으로 고리를 통과하게 된다.
	 */
	private static void recordAirborne(List<ServerPlayer> members, long now) {
		Set<UUID> present = new HashSet<>();
		for (ServerPlayer member : members) {
			present.add(member.getUUID());
			if (!member.onGround()) {
				LAST_AIRBORNE.put(member.getUUID(), now);
			}
		}
		LAST_AIRBORNE.keySet().retainAll(present);
	}

	/**
	 * 이 사람이 <b>고리가 지나가는 동안</b> 한 번이라도 떠 있었는가.
	 *
	 * <p>판정은 앞머리가 지난 뒤 {@link #JUMP_WINDOW_TICKS} 틱째에 내리므로, 지금부터 그만큼
	 * 거슬러 본 구간이 곧 「고리가 내 위에 있던 시간」이다.
	 *
	 * <p>기록이 아예 없으면 걸린 것으로 본다. 이 카드가 붙은 뒤로 한 번도 안 뛰었다는 뜻이다.
	 */
	private static boolean jumpedThrough(UUID memberId, long now) {
		Long last = LAST_AIRBORNE.get(memberId);
		return last != null && now - last <= JUMP_WINDOW_TICKS;
	}

	// ------------------------------------------------------------------ 판정

	/**
	 * 고리 뒷자락이 지나간 사람을 가른다.
	 *
	 * <p>경계를 <b>{@code (지난 틱 반경, 이번 틱 반경]}</b> 반열린 구간으로 잡는다. 고리는 한 틱에
	 * 0.5칸씩 건너뛰므로 「반경과 거리가 같은가」로 물으면 대부분의 사람이 <b>그냥 건너뛰어진다.</b>
	 * 구간으로 물으면 0 부터 {@code maxRadius} 까지 어느 거리든 정확히 한 번 덮인다.
	 *
	 * <p>피해는 없고 넉백도 없다. 여기서 사람에게 하는 일은 구속을 거는 것 하나뿐이다.
	 */
	private static void judge(ServerLevel end, List<ServerPlayer> members, Pulse pulse, int step,
			long now, TrialCatalog.Risk.EnderPulse risk) {
		double outer = judgeRadius(step, risk.travelTicks(), risk.maxRadius());
		double inner = judgeRadius(step - 1, risk.travelTicks(), risk.maxRadius());
		for (ServerPlayer member : members) {
			UUID memberId = member.getUUID();
			if (pulse.crossed().contains(memberId)) {
				continue;
			}
			double distance = distanceFromCenter(member.position());
			if (!(distance > inner) || !(distance <= outer)) {
				continue;
			}
			// 지나간 것은 뛰었든 아니든 지나간 것이다. 안 적으면 고리 끝에 붙어 바깥으로 달리는
			// 사람이 같은 파동에 다시 걸린다.
			pulse.crossed().add(memberId);
			if (jumpedThrough(memberId, now)) {
				continue;
			}
			root(end, member, risk.rootTicks());
		}
	}

	/**
	 * 발을 묶는다.
	 *
	 * <p>이 한 줄이 <b>팀 전원에게 퍼진다.</b> {@link EffectSync} 가 {@code shareStatusEffects}
	 * 를 보고 옮기는 것이고 <b>의도한 동작</b>이다(클래스 설명). 그래서 걸린 사람마다 따로 부르는
	 * 것이 낭비처럼 보이지만, 세기와 길이가 같으니 뒤에 걸린 사람은 남은 시간을 새로 채울 뿐이다 —
	 * 자기 순간에 실패했다는 사실이 그 사람의 화면에도 나타나야 한다.
	 *
	 * <p>{@code visible} 과 {@code showIcon} 을 켠다. 자막을 전부 걷어낸 판이라 <b>「내가 묶였다」를
	 * 말하는 것이 입자와 아이콘뿐</b>이다.
	 *
	 * <p>소리는 그 사람 자리에서 낸다. 바닐라 소리 사거리는 볼륨 1 이하면 16칸인데 아레나는 반경
	 * 42 라, 중앙 한 점에서 울리면 가장자리에 선 사람에게 닿지 않는다.
	 */
	private static void root(ServerLevel end, ServerPlayer member, int rootTicks) {
		if (rootTicks <= 0) {
			return;
		}
		Vec3 at = member.position();
		member.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, rootTicks, ROOT_AMPLIFIER,
				false, true, true));
		// 발밑에서 한 번 터뜨린다. 긴 형태라 나머지 셋도 「저 사람이 걸렸다」를 본다 — 구속이
		// 어차피 넷에게 다 오므로 누가 못 뛰었는지가 보여야 다음 고리에 쓸모가 있다.
		end.sendParticles(ParticleTypes.PORTAL, true, false, at.x, at.y + 0.1, at.z, 24,
				0.35, 0.05, 0.35, 0.0);
		end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE,
				1.0F, 0.6F);
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 고리가 <b>나에게</b> 닿기까지 남은 시간으로 경고 층을 올린다.
	 *
	 * <p>층과 소리는 {@link TrialWarning} 의 것을 그대로 쓴다. 사람이 다른 카드에서 이미 배운
	 * 신호라 새로 배울 것이 없고, 층이 언제 바뀌는지도 저장소가 한 곳에서 정한다.
	 *
	 * <p>남은 시간이 <b>사람마다 다르다</b>는 것이 이 카드의 특징이다. 고리는 중앙에서 출발하므로
	 * 가까이 선 사람에게 먼저 닿는다 — 같은 파동에 네 사람이 네 번 다른 순간에 뛰어야 한다.
	 *
	 * <p>소리는 <b>사람마다 그 자리에서</b> 울린다. 한 점에서 울리면 16칸 밖에는 안 들린다.
	 *
	 * <p>출발 틱은 층이 바뀌지 않았어도 무조건 한 번 울린다. 고리가 없던 직전 틱에는 층 자체가
	 * 없으니 「바뀌었다」가 참이어야 맞고, 무엇보다 <b>중앙 가까이 선 사람은 처음부터 마지막
	 * 층</b>이라 그렇지 않으면 경고를 한 번도 못 듣는다. 그 판단은
	 * {@link TrialRisks#stageJustChanged(int, int)} 에 이 사람의 예고 길이를 넘겨 맡긴다 —
	 * 「착지 충격」이 같은 규칙을 쓰므로 <b>한쪽만 고치지 말 것.</b>
	 */
	private static void warn(ServerLevel end, List<ServerPlayer> members, int step,
			TrialCatalog.Risk.EnderPulse risk) {
		for (ServerPlayer member : members) {
			Vec3 at = member.position();
			double distance = distanceFromCenter(at);
			if (distance > risk.maxRadius()) {
				// 고리가 닿지 않는 자리다. 그런데도 울리면 「경고는 들었는데 아무 일도 없다」가
				// 되고, 그 경험 하나가 다음 고리의 경고까지 무시하게 만든다.
				continue;
			}
			// 이 사람의 예고 길이. 고리가 출발한 틱(step 0)에 남아 있던 틱 수 그대로다.
			int lead = reachTick(distance, risk.travelTicks(), risk.maxRadius());
			int remaining = lead - step;
			if (remaining < 0) {
				// 이미 지나갔다. 지나간 고리가 계속 경고하면 다음 파동의 예고와 섞인다.
				continue;
			}
			TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
			if (stage == null) {
				continue;
			}
			if (!TrialRisks.stageJustChanged(remaining, lead)) {
				continue;
			}
			TrialWarning.sound(end, at, stage);
		}
	}

	// ------------------------------------------------------------------ 그리기

	/**
	 * 고리를 바닥에 그린다.
	 *
	 * <p>두 벌을 <b>같은 반경</b>에 겹쳐 찍는다. {@code CRIT} 은 4~10틱만 살아 앞머리를 선으로
	 * 남기고, {@code PORTAL} 은 40~49틱을 살며 자라서 지나간 자리를 채운다. 한 자리에서 나와
	 * 수명이 갈리는 것이므로 둘의 위치를 따로 계산할 이유가 없다 — 까닭은 클래스 설명에 있다.
	 *
	 * <p>반경이 0 인 출발 틱에는 그릴 것이 없다. 중앙 한 점에 400 발을 쏘아 봐야 덩어리 하나다.
	 */
	private static void draw(ServerLevel end, double groundY, double radius) {
		if (!(radius > 0.0)) {
			return;
		}
		double y = groundY + GROUND_OFFSET;
		ring(end, ParticleTypes.CRIT, y, radius, edgePoints(radius));
		ring(end, ParticleTypes.PORTAL, y, radius, wakePoints(radius));
	}

	/**
	 * 중앙을 도는 점들을 찍는다.
	 *
	 * <p><b>첫 {@code boolean} 을 {@code false} 로 되돌리지 말 것.</b> 짧은 형태는 서버에서
	 * 32칸으로 잘리고 클라이언트가 한 번 더 거른다({@link TrialWarning} 의 「거리 제한을 끄고
	 * 보낸다」). 이 고리는 반경 42 까지 가므로 되돌리는 순간 <b>바깥쪽 절반이 아무에게도 안
	 * 그려지고</b>, 피해가 없는 이 카드는 그대로 「갑자기 발이 묶이는 카드」가 된다.
	 *
	 * <p>둘째 {@code boolean}({@code alwaysShow}) 은 첫 깃발이 켜져 있으면 무의미하고, 사용자의
	 * 「파티클 줄이기」 설정을 우리가 뒤집을 이유도 없어 {@code false} 로 둔다.
	 */
	private static void ring(ServerLevel end, ParticleOptions type, double y, double radius,
			int points) {
		for (int index = 0; index < points; index++) {
			double angle = (Math.PI * 2.0 * index) / points;
			end.sendParticles(type, true, false,
					Math.cos(angle) * radius, y, Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 고리를 얹을 높이. 고리가 출발할 때 중앙에서 <b>한 번만</b> 잰다.
	 *
	 * <p>점마다 재면 한 틱에 하이트맵을 사백 번 두드리게 되고, 이 카드는 20초마다 4초씩 그린다.
	 * 이 카드는 블록을 한 칸도 건드리지 않으니 파동이 도는 동안 지면이 바뀌지도 않는다 —
	 * {@code DragonFireBarrage} 가 같은 이유로 놓을 때 한 번만 잰다.
	 *
	 * <p>중앙 섬은 평평해서 이 한 값으로 충분하고, 섬이 끊긴 바깥에서는 고리가 허공에 뜬다.
	 * 거기 설 수 있는 사람이 없으므로 그대로 둔다 — 안 그리면 오히려 「저쪽은 안전한가」로 읽힌다.
	 */
	private static double groundY(ServerLevel end) {
		BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				BlockPos.containing(0.0, 0.0, 0.0));
		return ground.getY() > end.getMinY() ? ground.getY() : FALLBACK_GROUND_Y;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 고리가 살아 있는 시간(틱).
	 *
	 * <p>앞머리가 {@code maxRadius} 에 닿은 <b>뒤에도</b> {@link #JUMP_WINDOW_TICKS} 만큼 남는다.
	 * 여기서 깎으면 가장 바깥에 선 사람만 창을 못 받아, 그 사람에게만 옛날의 한 틱짜리 판정이
	 * 적용된다.
	 *
	 * <p>다만 <b>주기를 넘기지는 않는다.</b> 카드에 적힌 {@code travelTicks} 가 주기보다 길면
	 * 다음 고리가 앞 고리를 밀어내는데, 한 자리에 고리 하나만 들고 있으므로 앞 고리는 <b>도중에
	 * 그냥 사라진다</b> — 아직 자기 차례를 기다리던 바깥쪽 사람들은 눈앞까지 온 고리가 증발하는
	 * 것을 본다. {@code TrialFireball.traceWindow} 가 같은 이유로 궤적을 주기로 깎는다.
	 */
	static int lifetime(int interval, int travelTicks) {
		int wanted = Math.max(0, travelTicks) + JUMP_WINDOW_TICKS;
		if (interval <= 0) {
			return wanted;
		}
		return Math.min(wanted, interval - 1);
	}

	/**
	 * 출발하고 {@code step} 틱 뒤 앞머리의 반경.
	 *
	 * <p>{@code step} 이 음수면 <b>음수를 그대로 돌려준다.</b> 안전장치가 아니라 규칙이다 —
	 * {@link #judgeRadius} 가 뒷자락을 「{@link #JUMP_WINDOW_TICKS} 틱 전의 앞머리」로 구하므로,
	 * 파동 첫머리에서 음수가 나와야 거리 0 인 사람도 걸러지지 않고 창을 온전히 받는다.
	 */
	static double radiusAt(int step, int travelTicks, double maxRadius) {
		if (travelTicks <= 0) {
			return maxRadius;
		}
		return maxRadius * step / travelTicks;
	}

	/**
	 * 이 틱에 판정을 받는 거리. 곧 고리 <b>뒷자락</b>의 반경이다.
	 *
	 * <p>앞머리에서 {@link #JUMP_WINDOW_TICKS} 틱 뒤처져 있고, 그 뒤처진 만큼이 「뛰었다」로
	 * 쳐 주는 창이다. 창과 두께를 한 값에서 뽑으므로 <b>보이는 고리와 봐 주는 구간이 어긋날 수
	 * 없다.</b>
	 *
	 * <p>{@code maxRadius} 를 넘지 않게 자른다. 앞머리가 멈춘 뒤에도 뒷자락은 다가오므로, 자르지
	 * 않으면 고리가 닿은 적 없는 42칸 밖 사람까지 묶인다.
	 */
	static double judgeRadius(int step, int travelTicks, double maxRadius) {
		return Math.min(maxRadius, radiusAt(step - JUMP_WINDOW_TICKS, travelTicks, maxRadius));
	}

	/**
	 * 앞머리가 그 거리에 닿는 틱. 고리가 출발한 틱부터 센다.
	 *
	 * <p>올림한다. 실제로 고리가 그 거리를 <b>넘어서는</b> 첫 틱이라야 「아직 안 왔다」가 참이고,
	 * 내림하면 경고가 한 틱 늦게 끝나 사람이 이미 지나간 고리를 기다린다.
	 */
	static int reachTick(double distance, int travelTicks, double maxRadius) {
		if (travelTicks <= 0 || !(maxRadius > 0.0) || !(distance > 0.0)) {
			return 0;
		}
		return (int) Math.ceil(distance * travelTicks / maxRadius);
	}

	/**
	 * 아레나 중앙에서 잰 거리. 높이는 보지 않는다.
	 *
	 * <p>고리는 바닥에 그려지고 중심은 언제나 {@code (0, ?, 0)} 이다. 세로를 섞으면 기둥 위에
	 * 올라간 사람의 거리가 실제보다 멀어져, <b>고리가 발밑을 지나가는데 판정은 비껴간다.</b>
	 */
	static double distanceFromCenter(Vec3 at) {
		return Math.sqrt(at.x * at.x + at.z * at.z);
	}

	/** 앞머리 고리에 찍을 점 수. */
	static int edgePoints(double radius) {
		return ringPoints(radius, EDGE_MAX_POINTS);
	}

	/** 몸통 고리에 찍을 점 수. */
	static int wakePoints(double radius) {
		return ringPoints(radius, WAKE_MAX_POINTS);
	}

	/**
	 * 그 반경에 찍을 점 수.
	 *
	 * <p>개수를 고정하면 반경이 커질수록 점 사이만 벌어진다. 그래서 {@link TrialWarning#POINT_GAP}
	 * 을 목표 간격으로 두고 개수를 거기서 뽑되, 상한에 걸리면 벌어지는 것을 받아들인다.
	 *
	 * <p>하한을 {@link TrialWarning#ringPoints} 에서 빌려 온다. 저장소가 「작은 고리는 이만큼은
	 * 찍는다」를 이미 한 곳에서 정해 두었는데, 여기에 같은 숫자를 다시 적으면 한쪽만 고쳐진다.
	 */
	private static int ringPoints(double radius, int cap) {
		if (!(radius > 0.0) || cap <= 0) {
			return 0;
		}
		int wanted = (int) Math.ceil((Math.PI * 2.0 * radius) / TrialWarning.POINT_GAP);
		return Math.min(cap, Math.max(TrialWarning.ringPoints(radius), wanted));
	}

	/**
	 * 그 반경에서 앞머리 점 사이가 실제로 벌어지는 거리(칸).
	 *
	 * <p>「고리가 고리로 읽히는가」를 숫자로 물을 수 있는 유일한 값이다. 점 수만 보면 상한에 걸린
	 * 큰 고리가 촘촘한 줄 알게 된다.
	 */
	static double edgeGap(double radius) {
		int points = edgePoints(radius);
		if (points <= 0) {
			return 0.0;
		}
		return (Math.PI * 2.0 * radius) / points;
	}
}

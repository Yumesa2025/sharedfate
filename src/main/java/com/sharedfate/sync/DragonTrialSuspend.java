package com.sharedfate.sync;

/**
 * 엔드 전투의 <b>일시 중단</b> — 「드래곤이 메모리에 없다」와 「드래곤이 죽었다」를 가른다.
 * <b>월드를 모른다 — 시험이 직접 굴린다.</b> 배선은 {@code DragonTrialManager.tickSessions} 에 있다.
 *
 * <h2>이것이 없어서 실제로 일어나던 일 (2026-10-18 본서버 로그)</h2>
 *
 * <p>「(팀원) left the game」 1초 뒤 「[END] 팀 'test' 드래곤 처치」. 전투 중에 팀원이 전부 접속을
 * 끊으면 바닐라 {@code EnderDragonFight.tick} 이 보스바에 사람이 없는 틱에 {@code TicketType.DRAGON}
 * 청크 티켓을 뗀다(26.3 바이트코드 — {@code getPlayers().isEmpty()} 이면
 * {@code removeTicketWithRadius(DRAGON, (0,0), 9)}). 섬 청크가 내려가며 드래곤 개체가 메모리에서
 * 빠지고, 전에는 {@code findDragon} 이 {@code null} 인 것을 그대로 「처치」로 읽었다.
 *
 * <ol>
 *   <li>최후의 저항 중이면 팀이 <b>서버 재시작까지 저항 V</b> — 나갔다 들어오면 무적 공략이었다</li>
 *   <li>체력 강화가 저장되지 않는 수정자라 다시 올라올 때 체력이 200 으로 잘려, 강화가 다시 붙으며
 *       <b>최대 체력으로 전부 회복</b>하거나 30% 문턱을 바로 넘어 <b>최후의 저항이 회복과 함께
 *       한 번 더</b> 열렸다</li>
 *   <li>카드·자리·시계가 다 사라지고 입장 수락창부터 처음부터였다</li>
 * </ol>
 *
 * <h2>무엇을 「죽었다」로 읽는가 — 확실한 근거 둘</h2>
 *
 * <ul>
 *   <li><b>죽은 드래곤 개체가 월드에 있다</b> — 체력 0 이 되면 {@code isAlive()} 가 거짓이 되고, 그 뒤
 *       200틱 동안 {@code tickDeath} 를 돌며 월드에 남는다. 지금까지 「처치」를 잡던 바로 그 틱이다.
 *       내려간 청크의 개체는 월드에서 빠지므로 이 칸은 「메모리에 없다」와 섞이지 않는다</li>
 *   <li><b>바닐라 {@code EnderDragonFight.dragonKilled}</b> — {@code setDragonKilled} 가 적는다. 26.3
 *       {@code EnderDragon.kill}({@code /kill})은 개체를 곧바로 지우면서 이 깃발을 함께 세우므로, 시체가
 *       남지 않는 길도 여기서 잡힌다</li>
 * </ul>
 *
 * <p>둘 다 아닌데 살아 있는 드래곤이 없으면 <b>내려갔을 뿐</b>이다. 죽음 이벤트({@code AFTER_DEATH})는
 * 쓰지 않는다 — 바닐라 죽음 연출({@code DragonDeathPhase})은 {@code setHealth(0)} 으로 끝나
 * {@code die()} 를 지나지 않는다.
 *
 * <h2>멈추는 것 둘 · 잇는 것 하나</h2>
 *
 * <ul>
 *   <li>{@link Verdict#AWAY} — 드래곤은 아직 보이는데 엔드에 팀원이 없다. 접속을 끊은 바로 그 틱이다.
 *       <b>드래곤이 내려가기 전에</b> 멈추므로 실제 체력을 그대로 읽고, 시련이 세운 개체들을 월드가
 *       살아 있을 때 거둔다</li>
 *   <li>{@link Verdict#ABSENT} — 살아 있는 드래곤이 없는데 죽은 근거도 없다. 엔드에 사람이 있어도
 *       섬에서 멀리 있으면(보스바 192칸 밖) 드래곤이 내려가고, 접속 직후 개체가 올라오기 전 몇 틱도
 *       이렇다</li>
 *   <li>{@link Verdict#FIGHTING} — 살아 있는 드래곤이 있고 엔드에 팀원이 있다. 멈춘 전투는 이것이
 *       되는 틱에 잇는다</li>
 * </ul>
 */
public final class DragonTrialSuspend {

	private DragonTrialSuspend() {
	}

	/** 이번 틱의 판정. */
	public enum Verdict {
		/** 살아 있는 드래곤이 있고 엔드에 팀원이 있다. 평소대로 돈다. */
		FIGHTING,
		/** 드래곤은 보이는데 엔드에 팀원이 없다. 멈춘다. */
		AWAY,
		/** 살아 있는 드래곤이 없는데 죽은 근거도 없다 — 메모리에서 내려갔다. 멈춘다. */
		ABSENT,
		/** 드래곤이 <b>진짜로</b> 죽었다. 전투가 닫힌다(처치). */
		KILLED
	}

	/**
	 * 이번 틱의 판정.
	 *
	 * <p>⚠ 살아 있는 드래곤이 하나라도 있으면 죽음 근거를 보지 않는다 — 크리스탈로 되살리면 바닐라
	 * 깃발이 내려가기 전 몇 틱이 있고, 드래곤이 둘인 판도 있다. 지금까지의 「처치」 판정
	 * ({@code findDragon == null})과 같은 순서다.
	 *
	 * @param liveDragon        살아 있는 드래곤이 엔드에 있는가
	 * @param deadDragonInWorld 죽은(체력 0) 드래곤 개체가 아직 월드에 있는가
	 * @param vanillaKilled     바닐라 {@code EnderDragonFight.dragonKilled}. 못 읽으면 거짓
	 * @param teamInEnd         이 팀의 팀원이 하나라도 엔드에 서 있는가
	 */
	public static Verdict judge(boolean liveDragon, boolean deadDragonInWorld, boolean vanillaKilled,
			boolean teamInEnd) {
		if (!liveDragon) {
			return deadDragonInWorld || vanillaKilled ? Verdict.KILLED : Verdict.ABSENT;
		}
		return teamInEnd ? Verdict.FIGHTING : Verdict.AWAY;
	}

	/** 이 판정이 전투를 멈추는가. */
	public static boolean pauses(Verdict verdict) {
		return verdict == Verdict.AWAY || verdict == Verdict.ABSENT;
	}

	/**
	 * 이을 때 입장 연출을 처음부터 다시 돌리는가.
	 *
	 * <p>연출(크리스탈을 거두고 → 드래곤을 끌어올리고 → 되살린다)이 <b>끝나기 전</b>, 그리고 그 뒤
	 * 입장 룰렛이 뜨기 전 숨 돌리는 틈까지다. 거둔 채 멈추면 되살리는 단계가 영영 안 와 크리스탈이
	 * 0개로 남는다. 연출이 반쯤 돈 자리에서 이으면 사람은 앞 절반을 못 본 채 크리스탈이 갑자기
	 * 선다 — 처음부터가 읽힌다. 크리스탈을 다시 거둬 다시 세우므로 잃는 것도 없다.
	 *
	 * @param entranceTick 입장 연출의 원점({@code DragonTrialSession.entranceTick})
	 * @param pausedAt     멈추는 틱
	 */
	public static boolean replaysEntrance(long entranceTick, long pausedAt) {
		return TrialRisks.elapsedSinceGrant(pausedAt, entranceTick) <= TrialEntrance.ROULETTE_DELAY_TICKS;
	}

	/**
	 * 이을 때 입장 연출을 처음부터 다시 돌리는가 — <b>입장 자리가 아직 소비되지 않았을 때만</b>(2026-10-18 검토).
	 *
	 * <p>룰렛이 뜨는 틱이 곧 {@code ROULETTE_DELAY_TICKS} 이고 룰렛이 도는 동안 게임 시각이 서므로, 룰렛 도중에
	 * 끊기면 멈추는 틱의 경과가 정확히 그 값이다 — 시각만 보면 「연출 중」과 가를 수 없어, 카드는 이미 쌓였는데
	 * 연출이 처음부터 다시 돌았다. 입장 자리({@code Trigger.ENTRY})가 줄에 남아 있으면 룰렛이 아직 안 끝난
	 * 것이고, 꺼내졌으면(카드를 쌓았거나 줄 카드가 없어 지나갔다) 연출을 다시 돌릴 까닭이 없다.
	 *
	 * @param entryPending 입장 자리가 아직 줄에 있는가
	 */
	public static boolean replaysEntrance(long entranceTick, long pausedAt, boolean entryPending) {
		return entryPending && replaysEntrance(entranceTick, pausedAt);
	}

	/**
	 * 재개 직후 팀원에게 거는 보호(저항 V)의 길이 — 3초. 도착 무적({@code DragonTrialManager.ARRIVAL_GRACE_TICKS})과
	 * 같은 값·같은 수단이다.
	 *
	 * <p>사람 결정(2026-10-18): 「이어 가기, 무적 없음」 — 최후의 저항의 진입 무적(8 + 3초)을 다시 주지 않는다.
	 * 다만 접속해 청크를 받는 몇 초 동안 포격·카드·패턴에 맞지 않게 이만큼만 준다. 하나씩 돌아오는 팀원도
	 * 엔드에 들어선 틱에 같은 3초를 받는다({@link #newArrivals}).
	 */
	public static final int RESUME_GRACE_TICKS = 60;

	/**
	 * 이번 틱에 <b>새로</b> 엔드에 선 팀원 — 지난 틱 명단에 없던 사람. 재개 직후 보호를 줄 사람이다.
	 *
	 * @param before 지난 틱 엔드에 서 있던 팀원. 멈췄다 이은 판이면 비어 있다(전원이 새로 온 것)
	 * @param now    이번 틱 엔드에 서 있는 팀원
	 */
	public static java.util.List<java.util.UUID> newArrivals(java.util.Set<java.util.UUID> before,
			java.util.Collection<java.util.UUID> now) {
		java.util.List<java.util.UUID> arrived = new java.util.ArrayList<>();
		for (java.util.UUID id : now) {
			if (id != null && !before.contains(id)) {
				arrived.add(id);
			}
		}
		return arrived;
	}

	/**
	 * 이을 때 카드의 받은 틱 — <b>멈춰 있던 만큼 뒤로 민다</b>(2026-10-18 검토).
	 *
	 * <p>엔드의 게임 시각은 아무도 없어도 흐른다. 받은 틱을 그대로 두면 종말의 비(30초) · 밤의 군세(20초) ·
	 * 다시 선 쇠창살(5초 창)처럼 창이 있는 카드를 받자마자 전원이 끊고 창보다 오래 있다 오는 것만으로 카드가
	 * 아무 일도 안 했다. 밀면 창이 멈춘 자리에서 이어지고, 주기 카드는 위상만 바뀐다.
	 *
	 * @param granted  받은 틱
	 * @param pausedAt 멈춘 틱. 모르면 {@link Long#MIN_VALUE}(옛 저장 · 강제 종료) — 그때는 밀지 않는다
	 * @param now      잇는 틱
	 */
	public static long resumedGrant(long granted, long pausedAt, long now) {
		if (pausedAt == Long.MIN_VALUE || now <= pausedAt) {
			return granted;
		}
		return granted + (now - pausedAt);
	}

	/**
	 * 최후의 저항을 이을 때 지대(보더) 시계의 경과 — 멈출 때 적어 둔 값 그대로, 음수(진입 연출 도중)와 모름은 0.
	 *
	 * <p>사람 결정(2026-10-18): 보더는 <b>나갈 때의 단계와 남은 시간표에서 이어 간다</b>(42 로 되돌리지 않는다).
	 * 진입 연출 도중에 끊긴 판은 연출을 다시 돌리지 않으므로 시계가 막 도는 자리(0 — 반변 42)에서 잇는다.
	 *
	 * @param saved 멈출 때의 경과({@code now - clockBase}). 모르면 {@link Long#MIN_VALUE}
	 */
	public static long resumedZoneClock(long saved) {
		return saved == Long.MIN_VALUE ? 0L : Math.max(0L, saved);
	}

	/**
	 * 강화를 <b>새로</b> 붙이거나 뗄 때의 체력 — 비율을 지킨다(2026-10-18 검토).
	 *
	 * <p>전에는 최대치만 바꾸고 체력은 그대로였다. 저장된 체력을 모르는 채 강화가 빠진 드래곤(0.41 에서 전투 중
	 * 올린 판 · 바닐라가 드래곤을 새로 만든 판)을 이으면 200 / 2400 이라 문턱 셋이 한꺼번에 터졌다.
	 * 비율로 옮긴 뒤 {@code ceiling} 으로 자른다 — 이미 지난 문턱 위로 되돌아가 「가득 찬 드래곤」이 되지 않게.
	 *
	 * @param health  지금 체력
	 * @param oldMax  바꾸기 전 최대 체력
	 * @param newMax  바꾼 뒤 최대 체력
	 * @param ceiling 넘지 않을 비율(0~1). 문턱을 모르면 1
	 */
	public static float rescaledHealth(float health, float oldMax, float newMax, float ceiling) {
		if (!(health > 0.0F) || !(oldMax > 0.0F) || !(newMax > 0.0F)) {
			return health;
		}
		float ratio = Math.min(health / oldMax, Math.max(0.0F, Math.min(1.0F, ceiling)));
		return Math.max(1.0F, Math.min(newMax, ratio * newMax));
	}

	/**
	 * 이미 지난 문턱으로 정한 체력 비율의 천장. 크리스탈로 문턱 위로 다시 오를 수 있으므로 정확한 값이 아니라
	 * 「적어도 이 위로는 아니었다」에 가까운 어림이다. 최후의 저항(30%)이 터졌으면 진입 회복 +20%p 를 더한다.
	 */
	public static float healthCeiling(java.util.Set<TrialCatalog.Trigger> fired) {
		if (fired.contains(TrialCatalog.Trigger.HEALTH_30)) {
			return DragonLastStand.ENTRY_HEALTH_RATIO + DragonLastStand.ENTRY_HEAL_FRACTION;
		}
		if (fired.contains(TrialCatalog.Trigger.HEALTH_50)) {
			return 0.50F;
		}
		if (fired.contains(TrialCatalog.Trigger.HEALTH_80)) {
			return 0.80F;
		}
		return 1.0F;
	}

	/**
	 * 멈출 때 지켜 둘 드래곤 체력.
	 *
	 * <p>최후의 저항 <b>진입 연출 도중</b>(체력 +20%p 가 차오르는 중)에 멈추면 차오를 몫을 <b>다 채운
	 * 값</b>을 지킨다. 이을 때는 회복을 다시 주지 않으므로, 여기서 반쯤 찬 값을 지키면 나갔다 들어오는
	 * 것만으로 회복이 반으로 깎인다. 연출 동안 드래곤은 보호막으로 피해를 받지 않으니 「다 채운
	 * 값」이 연출이 끝났을 때의 체력 그대로다.
	 *
	 * @param health      지금 체력
	 * @param max         최대 체력
	 * @param healing     최후의 저항 진입 연출이 회복을 주는 중인가
	 * @param entryHealth 진입한 틱의 체력
	 */
	public static float pausedHealth(float health, float max, boolean healing, float entryHealth) {
		if (!healing || !(max > 0.0F)) {
			return health;
		}
		return Math.max(health, DragonLastStand.healedHealth(entryHealth, max));
	}

	/**
	 * 이을 때 드래곤에 되돌릴 체력.
	 *
	 * <p>적어 둔 값이 없거나(옛 저장 · 강제 종료) 뜻이 없으면 <b>지금 체력 그대로</b>다 — 모를 때 손대지
	 * 않는다. 최대 체력을 넘지 않게 자른다. 0 이하는 적지 않는다 — 살아 있는 드래곤을 우리가 죽이는
	 * 길이 된다.
	 *
	 * @param saved   멈출 때 적어 둔 체력. 모르면 NaN
	 * @param current 지금 드래곤 체력
	 * @param max     지금 드래곤 최대 체력(강화를 다시 건 뒤)
	 */
	public static float restoredHealth(float saved, float current, float max) {
		if (!(saved > 0.0F) || !(max > 0.0F)) {
			return current;
		}
		return Math.min(saved, max);
	}

	/**
	 * 최후의 저항에서 <b>끝까지 치른</b> 오브젝트 파도 수.
	 *
	 * <p>도는 중이던 파도는 멈출 때 개체를 걷으므로 치른 것이 아니다 — 이을 때 다시 온다. 끝까지
	 * 부순 파도는 다시 주지 않는다. 나갔다 들어오는 것이 벌도 이득도 되지 않게 하는 셈이다.
	 *
	 * @param fired   지금까지 열린 파도 수
	 * @param running 지금 파도가 도는 중인가
	 */
	public static int clearedWaves(int fired, boolean running) {
		return Math.max(0, fired - (running ? 1 : 0));
	}
}

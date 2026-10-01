package com.sharedfate.sync;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 드래곤 <b>기본 패시브</b>의 자리. 시련과 섞이지 않는, 언제나 있는 판.
 *
 * <h2>왜 시련과 갈라 두는가</h2>
 *
 * <p>시련은 팀이 룰렛으로 <b>쌓는</b> 것이라 판마다 다르고, 패시브는 <b>언제나 있다.</b> 둘을
 * 한 자루에 넣으면 「이번 판이 왜 어려웠나」를 나눌 수 없다 — 카드 문서가 패시브 절을 따로 둔
 * 이유가 그것이고, 코드에서도 같은 선을 긋는다.
 *
 * <p>갈라 둔 값이 구조에서도 드러난다.
 *
 * <ul>
 *   <li><b>카드가 없다.</b> {@code TrialCatalog} 에 등록하지 않고 풀에도 넣지 않는다. 룰렛에
 *       패시브가 섞이면 「안 뽑히면 안 나오는 판」이 되어 언제나 있다는 말이 거짓이 된다</li>
 *   <li><b>{@code Risk} 봉인 인터페이스에 얹지 않는다.</b> 그쪽은 「카드에 적힌 값」을 나르는
 *       통로다. 패시브에는 적을 카드가 없으므로 값이 곧 클래스 상수다</li>
 *   <li><b>저장하지 않는다.</b> 시련은 무엇을 뽑았는지가 상태라 파일에 남지만, 패시브는 전투가
 *       열려 있으면 언제나 켜져 있다. 되살릴 것이 없다</li>
 * </ul>
 *
 * <h2>주기는 전투가 열린 틱부터 센다</h2>
 *
 * <p>{@code TrialRisks} 와 같은 이유다. 월드 시간으로 세면 판마다 위상이 같아져 <b>엔드에
 * 떨어지자마자 예고 없이 맞는</b> 판이 생긴다. 전투가 열린 틱을 기준으로 삼으면 받은 직후 한
 * 주기가 온전히 예고에 쓰인다.
 *
 * <h2>패시브를 하나 더 붙이려면</h2>
 *
 * <p>카드 문서에 셋이 더 적혀 있다 — 부채꼴 브레스 · 빠른 투사체 · 날개바람. 추상을 미리 만들어
 * 두지 않았으므로 <b>다음 사람이 할 일은 네 걸음뿐</b>이다.
 *
 * <ol>
 *   <li>{@code DragonFireBarrage} 와 같은 모양으로 파일을 하나 만든다. 진입점 시그니처를
 *       <b>그대로</b> 맞춘다 — {@code tick(ServerLevel, EnderDragon, List&lt;ServerPlayer&gt;,
 *       long granted, long now)} 와 {@code clearState()}</li>
 *   <li>{@link #tick} 에 호출 한 줄</li>
 *   <li>{@link #clearState()} 에 한 줄. <b>빠뜨리면 지난 판의 좌표가 새 월드에서 탄다</b> —
 *       컴파일도 로그도 조용한 종류의 사고라 {@code DragonPassivesTest} 가 여기를 지킨다</li>
 *   <li>{@code docs/드래곤-시련-카드.md} 의 「드래곤 기본 패시브」 절에 정한 값을 적는다</li>
 * </ol>
 *
 * <h2>지금 들어 있는 둘</h2>
 *
 * <ul>
 *   <li>{@link DragonFireBarrage} — <b>연쇄 포격.</b> 40초마다 아레나를 가로지르는 선이 선다</li>
 *   <li>{@link DragonPerch} — <b>착지.</b> 포디움에 내려앉으면 일정 시간 못 일어나고, 그 동안
 *       근접 피해만 받는다. ⚠ 이쪽은 <b>깃발을 세우는</b> 패시브라 {@link #tick} 의 이른 반환
 *       <b>앞</b>에서 불린다 — 까닭은 그 줄에 적어 두었다</li>
 * </ul>
 *
 * <p><b>인터페이스나 목록으로 묶지 말 것.</b> 지금 패시브가 둘이고 넷이 다 들어와도 넷이다.
 * 등록기를 만들면 「어디에 붙는지」를 읽는 데 파일 두 개를 더 열어야 하고, 얻는 것은 줄 하나를
 * 덜 적는 것뿐이다.
 */
public final class DragonPassives {

	private DragonPassives() {
	}

	/**
	 * 매 틱. 전투가 열려 있는 동안 패시브를 모두 돌린다.
	 *
	 * <p><b>드래곤이 살아 있을 때만 돈다.</b> 패시브는 드래곤이 하는 짓이므로, 죽은 뒤에도 포격이
	 * 이어지면 승리한 팀이 귀환 포털로 걸어가다 맞는다.
	 *
	 * @param granted 전투가 열린 틱({@link DragonTrialSession#startedTick()})
	 * @param now     지금 게임 시각. 시련 룰렛이 시간을 멈추면 이 값도 멈춰 위상이 함께 선다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, long granted, long now) {
		// ⚠ 이른 반환보다 먼저다. 「착지」는 믹스인이 읽는 깃발을 세우는 패시브라, 안 불린 틱에는
		// 직전 값이 그대로 남는다 — 사람이 아무도 없거나 드래곤이 사라진 틱에 깃발이 켜진 채
		// 멈추면 아무도 그것을 내리지 않는다. 그래서 그쪽은 null 을 스스로 받아 깃발을 내린다.
		DragonPerch.tick(end, dragon, members, granted, now);
		if (end == null || dragon == null || !dragon.isAlive()
				|| members == null || members.isEmpty()) {
			return;
		}
		DragonFireBarrage.tick(end, dragon, members, granted, now);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p><b>패시브를 하나 더 만들면 여기에 반드시 더하라.</b> 패시브는 값을 들고 있을 카드가 없어
	 * 저마다 정적 상태를 쓴다. 빠뜨리면 지난 판의 좌표·표적이 다음 판으로 샌다.
	 */
	public static void clearState() {
		DragonFireBarrage.clearState();
		DragonPerch.clearState();
	}
}

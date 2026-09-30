package com.sharedfate.sync;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 시련 카드 목록.
 *
 * <h2>왜 아직 JSON 이 아닌가</h2>
 *
 * <p>증강처럼 JSON 으로 빼는 것이 목표다. 다만 위험 타입마다 필요한 값이 다르고 그 목록이
 * 아직 두어 개 분량밖에 드러나지 않았다. 지금 파서를 만들면 카드 대여섯 장째에 다시 짠다.
 * <b>타입이 다섯쯤 굳은 뒤에 옮긴다.</b>
 *
 * <h2>카드는 룰렛으로 뽑는다</h2>
 *
 * <p>셋을 띄워 고르게 하지 않는다. 자리가 터지고 {@linkplain Trigger#delayTicks() 그 자리가 정한
 * 만큼} 지나면 <b>판이 멈추고</b> 이름이 돌다가 멈추며, 멈춘 것이 그 판의 시련이다. 카드가 한
 * 장뿐일 때도 굴러간다 — 고르게 하려면 자리당 최소 세 장이 있어야 하는데 카드를 채워 가는
 * 동안에는 맞출 수 없는 조건이다.
 *
 * <p>처음에는 타이틀 글자만 갈아 끼워 규약을 올리지 않았다. 그런데 <b>드래곤이 때리는 중에
 * 화면 위로 지나가는 글자는 읽히지 않았다.</b> 무엇을 받았는지 모른 채 싸우게 되고 전멸은 곧
 * 월드 삭제라, 증강처럼 판을 멈추고 화면을 띄우기로 했다({@link TrialFreeze}). 그 대가로
 * <b>통신 규약이 올라갔다.</b> 지금 값은 여기 적지 않는다 —
 * {@link com.sharedfate.net.SharedFateNetworking#PROTOCOL_VERSION} 이 그것을 들고 있고, 값을
 * 확인할 일이 있으면 <b>그쪽을 볼 것.</b>
 *
 * <p>⚠ 이 문단은 <b>제가 경고한 대로 낡았다.</b> 「여기에 숫자를 적으면 이 줄만 낡는다」고
 * 적어 두고는 같은 문단에 숫자를 적어, 규약이 <b>30 → 33 → 35</b> 로 오르는 내내 이 줄만
 * 어긋났다 — 30 에 멈춘 채 세 번을 지나쳤고, 33 으로 고쳐 놓은 뒤에 또 낡았다. 그래서 숫자를
 * 고치는 대신 <b>가리키게</b> 바꿨다. 다시 숫자를 적지 말 것.
 *
 * <h2>풀을 나누는 이유</h2>
 *
 * <p>입장 직후와 체력 30% 는 같은 무게일 수 없다. 트리거마다 풀을 따로 두면 <b>난이도 곡선</b>이
 * 생긴다. 카드 하나가 여러 풀에 속할 수 있으므로 「전멸과 80% 를 한 묶음으로」도, 「30% 에만
 * 나오는 카드」도 만들 수 있다.
 *
 * <p>지금은 <b>자리 여섯에 풀 여섯</b>이다. 두 자리가 한 풀을 나눠 쓰던 때가 있었는데
 * ({@code POOL_MIDDLE}·{@code POOL_LATE}), 그러면 앞 자리에서 뽑힌 카드가 뒤 자리의 풀에서도
 * 빠져 <b>자리 하나를 고치면 다른 자리가 같이 움직인다.</b> 자리마다 제 풀을 들면 그 얽힘이 없다.
 */
public final class TrialCatalog {

	/**
	 * 자리가 터지고 룰렛이 열리기까지 기다리는 틱.
	 *
	 * <p>지금 이 값을 쓰는 자리는 {@link Trigger#ENTRY} 하나뿐이다. 다른 차원에서 순간이동해
	 * 떨어진 직후라 <b>어디에 왔는지도 모르는 상태</b>이고, 거기에 화면을 겹치면 룰렛이 무엇
	 * 때문에 떴는지 읽히지 않는다. 떨어진 것을 먼저 겪게 하고 잠깐 뒤에 뽑는다.
	 *
	 * <h2>⚠ 이 값은 <b>입장 연출이 끝나는 시각과 묶여 있다</b></h2>
	 *
	 * <p>한동안 300틱(15초)이 여기 손으로 적혀 있었다. 그런데 사람이 입장 연출을 넣으면서
	 * <b>「입장연출 10초후 그뒤로 연출끝나면 룰렛시작」</b>으로 정했다 — 곧 기다리는 이유가
	 * 「떨어진 것을 먼저 겪게 한다」에서 <b>「연출이 끝날 때까지」</b>로 바뀌었다.
	 *
	 * <p>그래서 숫자를 적지 않고 {@link TrialEntrance#ROULETTE_DELAY_TICKS} 를 그대로 든다.
	 * 두 값을 따로 적어 두면 연출의 단계 길이를 하나만 고쳐도 <b>룰렛이 연출 위에 겹쳐 뜬다</b> —
	 * 룰렛이 뜨는 순간 {@link TrialFreeze} 가 판을 얼리므로 연출이 그 자리에서 멈춘 채 화면만
	 * 올라간다. 한 자리에 묶어 두면 그 어긋남이 <b>일어날 수 없다.</b>
	 *
	 * <p>단계별 길이와 그 근거는 {@code TrialEntrance} 에 있다. 여기를 직접 고치지 말 것.
	 */
	public static final int DELAY_SETTLE_TICKS = TrialEntrance.ROULETTE_DELAY_TICKS;

	/**
	 * 기다리지 않는다. 자리가 터진 <b>그 틱</b>에 룰렛이 열린다.
	 *
	 * <p>팀이 <b>스스로 만든 결과</b>인 자리에 쓴다 — 크리스탈을 깼거나 드래곤 체력을 깎았거나다.
	 * 원인이 이미 화면 한가운데에 있었으므로 「무엇 때문에 떴는지」를 물을 필요가 없고, 여기서
	 * 15초를 기다리면 읽을 시간이 생기는 것이 아니라 <b>「해냈는데 왜 아무 일도 없지」</b>가 된다.
	 */
	public static final int DELAY_NONE = 0;

	/**
	 * 그 자리의 카드가 <b>어떻게 뜨는가.</b>
	 *
	 * <p>처음에는 여섯 자리가 전부 룰렛이었다. 그런데 「판을 멈추고 이름이 돈다」는 <b>그 자체로
	 * 무게가 있는 연출</b>이라, 자리마다 카드가 한 장뿐이거나 이미 다른 것이 화면을 채우고 있으면
	 * 멈춤이 값어치보다 비싸진다. 그래서 무엇을 멈추고 무엇을 보여 줄지를 자리에 적는다.
	 */
	public enum Reveal {
		/** 판을 멈추고 이름이 돌다 멈춘다. 멈춘 것이 그 판의 시련이다. */
		ROULETTE,
		/**
		 * 판은 멈추지만 룰렛은 돌지 않는다. 정해진 카드의 이름과 설명만 보여 준다.
		 *
		 * <p>카드가 한 장뿐인 자리에 쓴다. 한 장짜리 풀에서 이름이 도는 것은 결과가 정해진 굴림을
		 * 보여 주는 것이라 연출이 거짓말이 된다.
		 *
		 * <h2>⚠ 지금 이것을 쓰는 자리가 <b>하나도 없다</b></h2>
		 *
		 * <p>{@link Trigger#HEALTH_80} 이 유일한 사용자였는데 사람이 <b>「80프로떄도 착지강화라고
		 * 카드가 안나오고 그냥 안띄워도되니 화면에 강화만 시켜주고」</b>라고 해서 {@link #SILENT}
		 * 로 옮겼다. 그래서 {@code DragonTrialManager.openFixedScreen} 과
		 * {@link TrialFreeze#FIXED_HOLD_TICKS} 는 <b>지금 한 번도 실행되지 않는다.</b>
		 *
		 * <p><b>그래도 지우지 말 것.</b> 셋이 갈려 있는 것 자체가 설계다 — 어떤 자리는 판을 멈추고
		 * 굴리고, 어떤 자리는 멈추되 굴리지 않고, 어떤 자리는 멈추지 않는다. 카드가 한 장뿐인
		 * 자리가 하나라도 생기면 그때 다시 쓸 값이고, 그때 다시 만들면 {@code FIXED_HOLD_TICKS}
		 * 의 「왜 60도 137도 아니고 100인가」를 처음부터 다시 셈해야 한다.
		 */
		FIXED_SCREEN,
		/** 아무것도 멈추지 않는다. 화면도 뜨지 않는다. */
		SILENT
	}

	/**
	 * 시련이 나오는 자리.
	 *
	 * <p>순서는 대체로 이 차례가 된다 — 크리스탈이 살아 있으면 드래곤 체력이 잘 안 깎이므로
	 * 크리스탈 쪽이 먼저 온다. 다만 순서를 코드가 강제하지는 않는다.
	 *
	 * <h2>지연은 자리의 성질이라 여기에 든다</h2>
	 *
	 * <p>전에는 관리자 쪽 상수 하나가 여섯 자리 전부에 똑같이 걸려 있었다. 그런데 기다리는 이유는
	 * <b>「무엇 때문에 떴는지 읽히게 한다」</b> 하나뿐이고, 그것이 필요한지 아닌지는 <b>자리마다
	 * 다르다.</b> 그래서 지연을 자리 옆에 값으로 적는다.
	 *
	 * <p>생성자가 인자로 요구하므로 <b>자리를 새로 만드는 사람은 지연을 정하지 않고는 만들 수
	 * 없다.</b> 상수 하나로 두면 새 자리가 조용히 15초를 물려받는데, 그 15초가 맞는지는 아무도
	 * 다시 묻지 않게 된다.
	 *
	 * <p>지금은 {@link #ENTRY} 하나만 0 이 아니다. 그래도 <b>{@code boolean} 하나로 줄이지 말
	 * 것</b> — 값이 자리에 붙어 있어야 어느 한 자리만 다르게 할 때 여기를 다시 뜯지 않는다.
	 *
	 * <h2>「어떻게 뜨는가」도 같은 이유로 여기에 든다</h2>
	 *
	 * <p>{@link Reveal} 도 생성자가 인자로 요구한다. 지연과 똑같은 판단이다 — 상수 하나나 기본값을
	 * 두면 <b>새 자리가 조용히 룰렛을 물려받고</b>, 그 자리에 룰렛이 맞는지는 아무도 다시 묻지
	 * 않는다. 정하지 않고는 자리를 만들 수 없게 둔다.
	 */
	public enum Trigger {
		/**
		 * 엔드에 들어서는 순간. <b>기다리는 자리는 이것뿐이다.</b>
		 *
		 * <p>다른 차원에서 끌려와 떨어진 직후라 어디에 왔는지도 모른다. 그 위에 화면을 겹치면
		 * 룰렛이 무엇 때문에 떴는지 읽히지 않는다.
		 *
		 * <p>이제 그 「잠깐」이 {@link TrialEntrance 입장 연출}의 길이다 — 크리스탈이 전부 사라지고,
		 * 10초 뒤 드래곤이 중앙 상공에 자리를 잡고, 크리스탈이 되살아난다. <b>그 연출이 끝나는
		 * 시각과 이 지연이 한 상수에 묶여 있다</b>({@link #DELAY_SETTLE_TICKS}).
		 */
		ENTRY("입장", DELAY_SETTLE_TICKS, Reveal.ROULETTE),
		/**
		 * 엔드 크리스탈이 처음 깨졌을 때.
		 *
		 * <p>자기가 조준하고 자기가 터뜨린 것이라 원인을 물을 필요가 없다. 곧바로 연다.
		 */
		FIRST_CRYSTAL("첫 크리스탈", DELAY_NONE, Reveal.ROULETTE),
		/** 엔드 크리스탈이 모두 깨졌을 때. 첫 크리스탈과 같은 이유로 곧바로 연다. */
		ALL_CRYSTALS("크리스탈 전멸", DELAY_NONE, Reveal.ROULETTE),
		/**
		 * 드래곤 체력이 80% 아래로 처음 내려갔을 때.
		 *
		 * <p>체력 문턱 셋도 <b>팀이 깎아서 만든 자리</b>다. 체력 막대가 눈앞에서 내려가는 것을
		 * 보며 때리던 중이라 원인이 분명하다. 곧바로 연다.
		 *
		 * <p>카드가 <b>한 장</b>뿐이라 룰렛을 돌리지 않는다. 그리고 <b>화면도 띄우지 않는다.</b>
		 *
		 * <h2>화면을 없앤 것은 사람이 정한 것이다</h2>
		 *
		 * <p>{@link Reveal#FIXED_SCREEN} 이던 자리다. 사람이 플레이해 보고 <b>「80프로떄도
		 * 착지강화라고 카드가 안나오고 그냥 안띄워도되니 화면에 강화만 시켜주고」</b>라고 정해
		 * {@link Reveal#SILENT} 로 옮겼다. 대신 그 틱에 {@link TrialEmpower} 가 「드래곤이 세졌다」를
		 * 소리·입자·화면 흔들림으로 낸다.
		 *
		 * <p>⚠ <b>대가가 있다.</b> 화면이 하던 일은 「착지 충격이 걸렸다」를 <b>글로</b> 알리는
		 * 것이었고, 이제 그 길이 없다. 다음 착지 때 바닥이 갑자기 터지는데 사람은 이유를 모른다 —
		 * 카드 문서가 「예고 없이 걸리면 무엇에 맞았는지 알 길이 없다」고 적어 두었던 그것이다.
		 * <b>사람이 그 사실을 알고 고른 것이므로 되돌리지 말 것.</b>
		 */
		HEALTH_80("체력 80%", DELAY_NONE, Reveal.SILENT),
		HEALTH_50("체력 50%", DELAY_NONE, Reveal.ROULETTE),
		/**
		 * 드래곤 체력이 30% 아래로 처음 내려갔을 때 — <b>최후의 저항.</b>
		 *
		 * <p>아무것도 멈추지 않고 화면도 띄우지 않는다. 이 자리는 굉음·화면 흔들림·엔더맨 소멸·
		 * 보스바 이름 변경이 <b>이미 「판이 바뀌었다」를 말한다.</b> 거기에 정지 화면을 얹으면
		 * 멈춤이 두 번 겹친다.
		 */
		HEALTH_30("체력 30%", DELAY_NONE, Reveal.SILENT);

		private final String label;
		private final int delayTicks;
		private final Reveal reveal;

		Trigger(String label, int delayTicks, Reveal reveal) {
			this.label = label;
			this.delayTicks = delayTicks;
			this.reveal = reveal;
		}

		public String label() {
			return label;
		}

		/**
		 * 이 자리가 터지고 룰렛이 열리기까지 기다리는 틱.
		 *
		 * <p>0 이면 <b>터진 그 틱</b>에 열린다 — 한 틱도 밀리지 않는다.
		 */
		public int delayTicks() {
			return delayTicks;
		}

		/** 이 자리의 카드가 어떻게 뜨는가 — 룰렛인가, 정해진 카드의 화면인가, 아무것도 없는가. */
		public Reveal reveal() {
			return reveal;
		}
	}

	/**
	 * 자리 여섯의 풀 여섯. 카드를 적을 때 이 이름으로 적는다.
	 *
	 * <p><b>풀을 나눠 쓰지 않는다.</b> 두 자리가 한 풀을 가리키면 앞 자리에서 뽑힌 카드가 뒤
	 * 자리의 풀에서도 빠지므로, 한 자리의 카드를 고치는 일이 다른 자리를 같이 움직인다. 자리를
	 * 새로 만드는 사람은 여기에 풀을 하나 더 적을 것.
	 */
	public static final Set<Trigger> POOL_ENTRY = EnumSet.of(Trigger.ENTRY);
	public static final Set<Trigger> POOL_FIRST_CRYSTAL = EnumSet.of(Trigger.FIRST_CRYSTAL);
	public static final Set<Trigger> POOL_ALL_CRYSTALS = EnumSet.of(Trigger.ALL_CRYSTALS);
	/**
	 * 카드가 <b>한 장</b>뿐인 자리다. 늘리려면 자리의 연출부터 볼 것 — 지금은
	 * {@link Reveal#SILENT} 라 <b>이름이 어디에도 안 뜬다.</b> 두 장이 되면 어느 것이 걸렸는지
	 * 알 길이 없어지므로, 카드를 더하는 사람은 {@link Trigger#HEALTH_80} 의 연출을 함께 정해야 한다.
	 */
	public static final Set<Trigger> POOL_HEALTH_80 = EnumSet.of(Trigger.HEALTH_80);
	public static final Set<Trigger> POOL_HEALTH_50 = EnumSet.of(Trigger.HEALTH_50);
	/**
	 * 최후의 저항 — <b>지금은 비어 있다.</b>
	 *
	 * <p>별개 보스전이라 따로 만든다. 빈 풀은 정상이고 그 자리는 그냥 지나간다
	 * ({@link #offerable}).
	 */
	public static final Set<Trigger> POOL_HEALTH_30 = EnumSet.of(Trigger.HEALTH_30);

	/**
	 * 위험 하나.
	 *
	 * <h2>왜 봉인 인터페이스인가</h2>
	 *
	 * <p>타입마다 필요한 값이 다르다. 칸을 하나로 합치면 낙뢰 카드에 「부활 개수」가, 부활
	 * 카드에 「예고 시간」이 붙어 <b>그 칸이 무슨 뜻인지 아무도 모르게 된다.</b>
	 *
	 * <p>실행기의 분기를 {@code default} 없는 switch 식으로 쓴다. 여기에 타입을 더하고 실행을
	 * 안 붙이면 <b>컴파일이 거절한다.</b> 이 저장소가 {@code GameOverCountdown.Reason} 에서
	 * 이미 쓰는 방식이다.
	 */
	public sealed interface Risk {

		/** 노리는 곳. */
		enum Aim {
			/** 그 사람이 조금 전 있던 자리. 계속 움직이면 빗나간다. */
			TRAIL,
			/**
			 * 아레나 안의 아무 곳. 서 있던 자리와 무관하다.
			 *
			 * <p>지점끼리는 <b>반경의 두 배보다 멀다</b>({@code TrialRisks} 가 강제한다). 겹침
			 * 구역이 생기면 한 사람이 한 틱에 두 번 맞아 즉사할 수 있기 때문이다.
			 */
			RANDOM_SPOT
		}

		/**
		 * 무엇이 떨어지는가.
		 *
		 * <h2>왜 값으로 가르는가</h2>
		 *
		 * <p>연출이 갈리지 않아 「낙뢰」가 <b>번개 대신 폭발 파티클과 폭발음</b>으로 나갔다. 카드
		 * 이름과 실제로 보이는 것이 달랐고, 사람은 TNT 가 터지는 줄 알았다. 실행기가 카드 id 를
		 * 보고 연출을 고르게 만들면 카드를 늘릴 때마다 같은 실수가 되풀이되므로 값으로 적는다.
		 */
		enum Impact {
			/** 그 자리가 터진다. 폭발 파티클과 폭발음. */
			EXPLOSION,
			/** 진짜 번개가 내리친다. */
			LIGHTNING
		}

		/**
		 * 예고한 자리에 무언가 떨어진다.
		 *
		 * <h2>피해 × 겹칠 수 있는 개수가 팀 체력을 넘으면 안 된다</h2>
		 *
		 * <p>이 전투의 설계 원칙은 <b>「즉사 메커닉 0개」</b>다. 팀 공유 체력은 20 이고 전멸은 곧
		 * 월드 삭제다. 그런데 이 레코드는 <b>{@code damage} 와 {@code count} 가 따로 적히므로</b>
		 * 한쪽만 올린 사람이 즉사 카드를 만들 수 있다 — {@code count} 가 여럿이면 한 사람이 여러
		 * 고리에 동시에 들 수 있기 때문이다.
		 *
		 * <p>{@link Aim#RANDOM_SPOT} 은 {@code TrialRisks} 가 <b>지점끼리 반경의 두 배보다 멀게</b>
		 * 강제해 겹침 구역을 없앤다. 그래서 {@code count} 를 열로 올려도 한 사람이 받는 것은 한 발
		 * 뿐이다. <b>그 규칙을 지우면 여기 적힌 값들이 그 순간 즉사가 된다.</b>
		 * {@code TrialRisksTest} 가 「가장 나쁜 경우의 한 틱 피해」를 카드 값에서 직접 계산해
		 * 20 미만인지 본다 — 값을 올리는 사람은 거기서 멈춘다.
		 *
		 * @param aim      발자국을 노리는가 아무 곳인가
		 * @param impact   떨어진 자리에서 무엇이 보이고 들리는가. 실행기가 이 값으로 갈린다
		 * @param interval 떨어지는 간격(틱)
		 * @param lookback 몇 틱 전 발자국을 노리는가. {@link Aim#RANDOM_SPOT} 이면 쓰지 않는다
		 * @param damage   반경 안에 남아 있는 사람이 받는 피해
		 * @param radius   피해 반경(블록). 바닥 고리도 이 크기로 그린다
		 * @param launch   맞은 사람을 띄우는 높이(블록). 0 이면 띄우지 않는다.
		 *                 띄우는 동안은 낙하 피해를 면제한다 — 이 카드의 위험은 노출이지 낙사가 아니다
		 * @param count    한 번에 몇 군데인가. {@link Aim#TRAIL} 이면 <b>무작위로 뽑는 사람 수</b>다.
		 *                 {@link Aim#RANDOM_SPOT} 은 최소 간격을 지킬 자리를 못 찾으면 적힌 것보다
		 *                 적게 나올 수 있다 — 겹치느니 한 발 빠지는 쪽이다
		 */
		record DelayedStrike(Aim aim, Impact impact, int interval, int lookback, float damage,
				double radius, double launch, int count) implements Risk {
		}

		/**
		 * 흑요석 기둥에서 날아오는 불덩이.
		 *
		 * <h2>왜 바닐라 화염구를 쓰지 않는가</h2>
		 *
		 * <p>{@code LargeFireball} 의 블록 파괴는 {@code mobGriefing} 게임룰에 묶여 있어 우리가
		 * 끌 수 없다. 끄면 크리퍼까지 함께 꺼진다. 불도 폭발에 딸려 붙는다. 엔드 섬에 구멍이
		 * 뚫리면 다음 전투부터 발판이 달라지므로 <b>궤적을 파티클로 그리고 착탄을 직접 처리</b>한다 —
		 * 보이는 것은 같고 값은 전부 우리 것이다.
		 *
		 * @param interval   발사 간격(틱)
		 * @param traceTicks 궤적을 보여 주는 시간. 이만큼 날아온다
		 * @param damage     착탄 반경 안의 사람이 받는 피해
		 * @param radius     착탄 반경(블록)
		 * @param count      한 번에 몇 발. 사람마다 한 발씩 노린다
		 */
		record TracedProjectile(int interval, int traceTicks, float damage, double radius,
				int count) implements Risk {
		}

		/**
		 * 크리스탈을 깨기 어렵게 만든다.
		 *
		 * <p>피해를 주지 않고 <b>시간을 빼앗는</b> 축이다. 보상이 없는 이상 한 풀의 카드가 전부
		 * 「맞으면 아프다」면 룰렛이 무엇을 뽑든 같은 판이 된다.
		 *
		 * @param arrowImmune  참이면 크리스탈이 투사체에 맞지 않는다. 올라가서 깨야 한다
		 * @param restoreCage  참이면 모든 크리스탈에 쇠창살이 다시 생긴다. 없던 탑에도 생긴다
		 * @param digSlowdown  참이면 팀 전원의 <b>채굴 속도가 15% 깎인다</b>
		 *                     ({@code TrialCrystalGuard.DIG_SLOWDOWN_MULTIPLIER}).
		 *                     ⚠ <b>채굴 피로 상태이상이 아니다.</b> 예전에는 채굴 피로 I 을 걸었는데
		 *                     그것은 바닐라에서 {@code 0.3^(등급+1)} 이라 <b>70% 감소</b>이고, 사람이
		 *                     플레이해 보고 <b>「무딘곡괭이 이거 채굴피로1은 심하고 채굴 속도
		 *                     15프로감소로」</b>라고 정했다. 15% 를 내는 바닐라 상태이상이 없어
		 *                     {@code Player.getDestroySpeed} 에 직접 곱한다
		 */
		record CrystalGuard(boolean arrowImmune, boolean restoreCage, boolean digSlowdown)
				implements Risk {
		}

		/** 드래곤이 누구를 노리는가. */
		enum Focus {
			/** 무작위 한 명. 주기마다 다시 고른다. */
			RANDOM,
			/** 크리스탈을 가장 최근에 깬 사람. 깬 적이 없으면 무작위로 물러난다. */
			CRYSTAL_BREAKER
		}

		/**
		 * 드래곤의 공격이 한 사람에게 쏠린다.
		 *
		 * <h2>페이즈를 건드리지 않는다 — 한 번 크게 틀린 자리다</h2>
		 *
		 * <p>처음에는 드래곤을 <b>돌진 페이즈로 밀어넣어</b> 만들었다. 실제로 플레이하니 두 가지가
		 * 드러났다. 하나는 「타겟팅만 뜨고 아무것도 안 온다」(바닐라가 이미 같은 사람을 노리고 있어
		 * 우리 개입이 무효였다), 다른 하나가 더 나빴다 — <b>드래곤이 착지를 아예 하지 않게 됐다.</b>
		 *
		 * <p>바닐라는 드래곤이 원을 <b>한 바퀴 다 돌아 경로가 끝난 틱</b>에만 「착지할까」를 굴린다.
		 * 우리가 주기적으로 끼어들면 돌아올 때마다 경로가 처음부터 다시 깔려 그 틱이 영영 오지
		 * 않는다. 시간 비율의 문제가 아니라 <b>주기를 끊는 것</b>이 문제였다.
		 *
		 * <p>그래서 이제 <b>우리가 직접 구체를 날린다.</b> 페이즈를 한 번도 만지지 않으므로 착지도
		 * 크리스탈도 바닐라 그대로다. <b>이 판단을 되돌리지 말 것.</b>
		 *
		 * @param focus     대상을 고르는 법
		 * @param markTicks 한 사람을 노리는 시간. 이 동안 {@code shots} 발이 고르게 나간다
		 * @param restTicks 그다음 쉬는 시간. 쉬는 틈이 없으면 다른 카드와 겹칠 때 버거워진다
		 * @param shots     {@code markTicks} 동안 날리는 구체 수
		 * @param damage    구체 한 발의 피해. <b>전부 맞으면 {@code shots × damage}</b> 라
		 *                  팀 체력 20 을 쉽게 넘긴다 — 발 사이가 벌어져 있어 도망칠 수 있다는 전제다
		 */
		record DragonFocus(Focus focus, int markTicks, int restTicks, int shots, float damage)
				implements Risk {
		}

		/**
		 * 크리스탈이 되살아난다.
		 *
		 * <p>연출이 도는 동안 드래곤은 중앙 상공에 머문다 — <b>때릴 수 없는 시간이 곧 대가</b>다.
		 *
		 * @param count     되살릴 개수. 남은 자리가 그보다 적으면 있는 만큼만
		 * @param showTicks 복구 연출 길이. 이 동안 드래곤이 중앙에 머문다
		 * @param heal      되살아난 크리스탈 하나당 드래곤이 회복하는 양
		 */
		record CrystalRevive(int count, int showTicks, float heal) implements Risk {
		}

		/**
		 * 중앙에서 바닥 고리가 퍼져 나간다.
		 *
		 * <p>피해가 없다. 이 카드가 빼앗는 것은 체력이 아니라 <b>발</b>이다 — 고리가 지나가는 순간
		 * 바닥을 딛고 있으면 잠시 못 움직인다. 점프하면 통과하므로 요구하는 행동이 하나뿐이다.
		 *
		 * @param interval    고리가 새로 퍼지기 시작하는 간격(틱)
		 * @param travelTicks 중앙에서 {@code maxRadius} 까지 퍼지는 데 걸리는 틱
		 * @param maxRadius   고리가 닿는 마지막 반경(블록)
		 * @param rootTicks   고리에 걸린 사람이 묶이는 시간(틱)
		 */
		record EnderPulse(int interval, int travelTicks, double maxRadius, int rootTicks)
				implements Risk {
		}

		/**
		 * 크리스탈 하나를 깨면 다른 하나가 잠시 보호막을 두른다.
		 *
		 * <p>주기가 없다. 발동을 정하는 것은 시간이 아니라 <b>팀의 행동</b>이다.
		 *
		 * @param shieldTicks 보호막이 남아 있는 시간(틱)
		 */
		record CrystalLink(int shieldTicks) implements Risk {
		}

		/**
		 * 크리스탈 하나가 달아오르고, 제때 못 부수면 빔이 한 사람을 문다.
		 *
		 * @param fuseTicks       달아오른 크리스탈을 부술 수 있는 시간(틱). 넘기면 빔이 시작된다
		 * @param beamTicks       빔이 붙어 있는 시간(틱)
		 * @param restTicks       빔이 끝나거나 제때 부순 뒤 다음 크리스탈까지 쉬는 시간(틱)
		 * @param damagePerSecond 빔에 물린 사람이 <b>1초마다</b> 받는 피해
		 */
		record CrystalOvercharge(int fuseTicks, int beamTicks, int restTicks, float damagePerSecond)
				implements Risk {
		}

		/**
		 * 가장자리에서 중앙으로 소용돌이가 밀려온다.
		 *
		 * <h2>⚠ 미는 방향을 뒤집었다 — 이제 바깥이다</h2>
		 *
		 * <p>처음에는 <b>안쪽</b>으로만 밀었다. 엔드 중앙 섬은 사방이 허공이고 공유 체력이라
		 * 한 사람의 낙사가 팀 전체를 끝내므로, 「안쪽으로만 민다」가 이 카드를 「강한 넉백 금지」의
		 * 예외로 만든 조건이었다.
		 *
		 * <p>그런데 실제로 해 보니 <b>「한 번 밀쳐지면 끝」</b>이었다. 폭풍이 오는 방향으로
		 * 떠밀려 들어가니 다음 틱에는 이미 폭풍 뒤였다. 그래서 <b>바깥으로, 닿아 있는 동안
		 * 계속</b> 미는 쪽으로 뒤집었다 — 폭풍 앞에서 계속 떠밀리며 옆으로 빠져나가야 한다.
		 *
		 * <p><b>그 대신 실행기가 미는 자리에 천장을 건다.</b> 어떤 세기를 넣어도 목적지가 섬
		 * 안이다. 이것이 이제 유일한 장치다 — <b>지우면 이 카드는 그날로 전멸 카드다.</b>
		 *
		 * @param speedPerSecond 소용돌이가 1초에 나아가는 거리(블록)
		 * @param count          한 번에 도는 소용돌이 수
		 * @param damage         닿은 사람이 받는 피해
		 * @param knockback      미는 세기의 배율. 방향은 <b>바깥</b>이고 실행기가 섬 안으로
		 *                       천장을 건다
		 * @param restTicks      소용돌이들이 중앙에 닿은 뒤 새로 시작하기까지 쉬는 시간(틱)
		 */
		record EnderStorm(int count, double speedPerSecond, float damage, double knockback,
				int restTicks) implements Risk {
		}

		/**
		 * 엔드가 마른다 — 물과 용암이 사라지고 다시 놓을 수 없게 된다.
		 *
		 * <p><b>칸이 하나도 없다.</b> 한 번 터지고 끝나는 카드인데 「얼마나」를 정할 자리가 없기
		 * 때문이다. 다만 터지고 사라지지는 않는다 — 다시 설치하지 못하는 금지가 전투가 끝날
		 * 때까지 남는다.
		 */
		record DryWorld() implements Risk {
		}

		/**
		 * 엔드의 엔더맨이 한꺼번에 적대가 된다.
		 *
		 * <p>처음에는 <b>엔드의 엔더맨 전부</b>였다. 실제로 해 보니 「너무 빡세다」 — 엔드는
		 * 엔더맨이 끝없이 깔린 곳이라 「전부」가 사실상 무한이었다. 그래서 <b>사람 가까이 있는
		 * 몇 마리</b>로 좁힌다.
		 *
		 * @param hostileTicks 적대인 시간(틱). 지나면 원래대로 돌아간다
		 * @param radius       팀원에게서 이 거리 안에 있는 놈만 깨운다(블록)
		 * @param maxMobs      깨우는 최대 마리 수. 넘치면 가까운 순으로 자른다
		 */
		record NightHost(int hostileTicks, double radius, int maxMobs) implements Risk {
		}

		/**
		 * 정해진 시간 동안 아레나 곳곳에 표시가 떴다가 착탄한다.
		 *
		 * <p>지점은 {@code TrialRisks} 의 <b>겹침 금지</b> 목록에 올린다. 한 볼리 안에서만이 아니라
		 * <b>그때 살아 있는 다른 카드의 지점과도</b> 겹치면 안 된다 — 「낙뢰」와 이 카드가 동시에
		 * 돌 수 있고, 겹친 자리는 한 틱에 두 발이다.
		 *
		 * @param durationTicks 이 카드가 비를 내리는 전체 시간(틱). 한 번 쓰고 끝난다
		 * @param minInterval   다음 볼리까지의 가장 짧은 간격(틱)
		 * @param maxInterval   다음 볼리까지의 가장 긴 간격(틱)
		 * @param minSpots      한 볼리의 가장 적은 지점 수
		 * @param maxSpots      한 볼리의 가장 많은 지점 수
		 * @param warnTicks     표시가 뜨고 착탄까지의 시간(틱)
		 * @param damage        반경 안에 남아 있는 사람이 받는 피해
		 * @param radius        피해 반경(블록). 바닥 표시도 이 크기다
		 */
		record EndRain(int durationTicks, int minInterval, int maxInterval, int minSpots,
				int maxSpots, int warnTicks, float damage, double radius) implements Risk {
		}

		/**
		 * 드래곤이 착지할 때마다 중앙에서 충격이 퍼진다.
		 *
		 * <p>주기가 없다. 발동을 정하는 것은 시간이 아니라 <b>드래곤의 착지</b>다.
		 *
		 * <h2>한 번 착지에 고리가 여럿이다</h2>
		 *
		 * <p>처음에는 고리 하나였는데 「더 세게, 더 넓게」가 나왔다. 반경을 다섯 배로 늘리고
		 * 미는 거리를 두 배로 올렸고, <b>2초 간격으로 세 번</b> 퍼지게 했다. 한 번 비켰다고
		 * 끝이 아니라 <b>세 번을 읽어야</b> 한다.
		 *
		 * <h2>⚠ 이제 계산으로는 섬 밖으로 나간다 — 천장이 유일한 장치다</h2>
		 *
		 * <p>예전에는 반경 15 + 넉백 8 = <b>23칸</b>이라 섬(반경 40, 기둥 42) 안에 저절로
		 * 머물렀고, 그 산수가 「강한 넉백 금지」의 예외 조건이었다. 지금 값은 75 + 16 =
		 * <b>91칸</b>이라 그 조건이 <b>깨졌다.</b>
		 *
		 * <p>그래서 실행기가 <b>미는 자리에 천장을 건다</b> — 어떤 값을 넣어도 목적지가 섬
		 * 안이다. 공유 체력이라 한 사람의 낙사가 팀 전체를 끝내고 그것이 곧 월드 삭제이므로,
		 * <b>그 천장을 지우면 이 카드는 그날로 전멸 카드다.</b>
		 *
		 * @param travelTicks   중앙에서 {@code maxRadius} 까지 퍼지는 데 걸리는 틱
		 * @param maxRadius     고리가 닿는 마지막 반경(블록). 섬(40)보다 커도 된다 —
		 *                      허공 위를 지나는 구간에는 사람이 없다
		 * @param damage        고리가 지나갈 때 바닥을 딛고 있던 사람이 받는 피해
		 * @param knockback     바깥으로 미는 거리(블록). <b>실행기가 섬 안으로 천장을 건다</b>
		 * @param ringCount     한 번 착지에 퍼지는 고리 수
		 * @param ringIntervalTicks 고리와 고리 사이 간격(틱)
		 */
		record LandingShock(int travelTicks, double maxRadius, float damage, double knockback,
				int ringCount, int ringIntervalTicks) implements Risk {
		}

		/**
		 * 핫바의 몇 칸이 굳는다.
		 *
		 * <p>쌓이지 않는다. 주기마다 굳는 칸이 <b>옮겨 다닐 뿐</b> 개수는 늘 {@code slots} 다.
		 *
		 * @param interval 굳는 칸이 옮겨 가는 간격(틱)
		 * @param slots    한 번에 굳는 칸 수
		 */
		record HotbarLock(int interval, int slots) implements Risk {
		}
	}

	/**
	 * 카드 한 장.
	 *
	 * @param id          저장에 쓰는 식별자
	 * @param name        룰렛과 화면에 뜨는 이름
	 * @param description 무엇이 일어나는지. 뽑힌 순간 읽는 글이다
	 * @param pools       이 카드가 나올 수 있는 자리들
	 * @param risks       이 카드가 거는 위험들. 여럿이면 함께 돈다
	 */
	public record Trial(String id, String name, String description, Set<Trigger> pools,
			List<Risk> risks) {

		/** 위험이 하나뿐인 흔한 경우. */
		public Trial(String id, String name, String description, Set<Trigger> pools, Risk risk) {
			this(id, name, description, pools, List.of(risk));
		}
	}

	/**
	 * 카드 목록. 내용은 {@code docs/드래곤-시련-카드.md} 와 짝이다.
	 *
	 * <p><b>빈 풀은 정상이다.</b> 카드를 채워 가는 동안 자리가 비면 그 자리는 그냥 지나간다.
	 *
	 * <h2>⚠ 피해 값은 <b>완전무장</b>을 전제로 잡혀 있다 — 맨몸에게는 즉사다</h2>
	 *
	 * <p>전에는 「팀 공유 체력 20, 한 틱 피해 20 미만」을 <b>맨몸 날값</b>으로 쟀다. 그런데 시련은
	 * 엔드까지 온 팀이 받는 <b>추가 난이도</b>이고, 사람이 「다이아셋 + 보호 인챈트까지 하고 맞는
	 * 것까지 고려해야 한다」고 정했다. 그래서 큰 카드의 피해는 <b>다이아 풀셋(방어 20 · 강도 8) +
	 * 보호 IV 네 곳(EPF 16)</b>을 통과한 뒤의 값으로 잡혀 있다.
	 *
	 * <p>26.3 이 실제로 쓰는 식이다. 지어낸 것이 아니라 {@code CombatRules} 와
	 * {@code Player.hurtServer} 의 바이트코드에서 읽었다.
	 *
	 * <ol>
	 *   <li><b>난이도</b> — 하드에서 {@code 피해 × 3 / 2}. 방어보다 <b>먼저</b> 걸린다. 다만
	 *       <b>피해 종류가 정한다</b> — {@code explosion} 은 {@code scaling: always} 라 걸리고,
	 *       {@code lightning_bolt} 는 {@code when_caused_by_living_non_player} 라 <b>가해자를 달지
	 *       않은 우리 피해원에서는 안 걸린다</b>. {@code magic} 은 거기에 더해
	 *       {@code #bypasses_armor} 라 방어 자체를 지나친다</li>
	 *   <li><b>방어</b> — {@code 피해 × (1 − clamp(방어 − 피해/(2 + 강도/4), 방어/5, 20) / 25)}</li>
	 *   <li><b>보호</b> — {@code 피해 × (1 − min(20, EPF) / 25)}. EPF 16 이면 0.36 배다</li>
	 * </ol>
	 *
	 * <p>기준은 사람이 정한 <b>「큰자리는 3대 맞으면 죽는거로 생각하자」</b>다. 팀 체력 20 을 세
	 * 대로 나누면 한 대에 <b>6.67</b> 이고, 위 식을 거꾸로 풀면 난이도 곱이 <b>없는</b> 카드가
	 * <b>34.18</b>, <b>있는</b> 카드가 <b>22.79</b> 다.
	 *
	 * <h2>적히는 값은 정수라 두 그룹이 딱 같아지지 않는다 — 맞추려 들지 말 것</h2>
	 *
	 * <p>역산값을 <b>올림</b>한다. 34 로 내리면 세 대에 <b>19.83</b> 이라 하트 한 칸도 안 되는
	 * 차이로 살아남아 사람이 정한 「3대에 죽는다」가 거짓이 된다. 그래서 곱이 없는 쪽은
	 * <b>35</b>(세 대 <b>20.79</b>), 있는 쪽은 <b>23</b>(세 대 <b>20.31</b>) 이다.
	 *
	 * <p>둘이 20.79 와 20.31 로 <b>다른 것은 정상이다.</b> 카드에 적히는 것이 정수라 그보다 가깝게
	 * 맞출 수 없다 — 둘 다 「3대에 죽는다」를 만족하면 그것으로 끝이다. <b>소수점을 맞추려고
	 * 값을 다시 굴리지 말 것.</b>
	 *
	 * <p>⚠ <b>죽어서 장비를 잃고 돌아온 사람에게는 이 감쇠가 하나도 안 걸린다.</b> 적힌 34 를 그대로
	 * 맞고 팀 체력이 20 이라 <b>맨몸이면 한 대에 전멸</b>이고, 전멸은 곧 월드 삭제다. 사람은 그
	 * 사실을 듣고도 「3대」로 가자고 했으므로 <b>되돌리지 말 것</b> — 다만 이 값을 읽는 사람은
	 * 「즉사 메커닉 0개」가 이제 <b>무장 기준의 약속</b>이라는 것을 알고 있어야 한다.
	 * {@code TrialRisksTest} 의 안전 시험도 같은 기준으로 옮겨 두었다.
	 */
	private static final List<Trial> TRIALS = List.of(
			new Trial("sharedfate:ground_strike", "자리 폭격",
					"12초마다 한 사람이 2초 전에 있던 자리가 터집니다. 맞으면 하늘로 떠오릅니다.",
					POOL_ENTRY,
					// 18 은 맨몸 날값으로 잡은 천장이었다. 실제로 완전무장하고 맞아 보니
					// 「안 아프다」였다 — 다이아 풀셋 + 보호 IV 를 지나면 18 이 2.5 로 들어와
					// 팀 체력 20 의 1/8 이었다. 사람이 「큰자리는 3대 맞으면 죽는거로 생각하자」고
					// 정해, 무장 기준 한 대에 6.67 이 되는 값으로 다시 풀었다.
					//
					// 35 인 것은 이 카드에 난이도 곱이 안 걸리기 때문이다. TrialRisks 가 쓰는
					// 피해원이 damageSources().lightningBolt() 인데, lightning_bolt 의 scaling 은
					// when_caused_by_living_non_player 라 가해자를 달지 않은 우리 피해원에서는
					// 참이 되지 않는다. 그래서 적힌 값이 그대로 방어·보호를 지난다.
					//
					// 34 가 아니라 35 인 이유를 남긴다. 6.67 의 정확한 해는 34.18 인데 내림해서
					// 34 로 두면 무장 기준 한 대가 6.61 이고 세 대에 19.83 이라 — 하트 한 칸도
					// 안 되는 차이로 살아남는다. 사람이 정한 것은 「3대에 죽는다」이므로
					// 올림해야 그 말이 성립한다. 35 는 한 대 6.93, 세 대 20.79 로 죽는다.
					//
					// 「기둥 화염구」·「종말의 비」 쪽은 23 이라 세 대에 20.31 이다. 두 그룹이
					// 20.79 와 20.31 로 다른 것은 정상이다 — 적히는 값이 정수라 그보다 가깝게
					// 맞출 수 없고, 둘 다 「3대에 죽는다」를 만족한다. 소수점을 맞추려고 값을
					// 다시 굴리지 말 것.
					//
					// ⚠ 맨몸이면 35 가 감쇠 없이 그대로 들어가 팀 체력 20 을 한 대에 넘긴다.
					// 그것을 알고 고른 값이다 — 클래스 설명의 「피해 값은 완전무장을 전제로」를 볼 것.
					//
					// 반경은 2.0 이었다. 사람이 다시 플레이해 보고 「ground attack 이거 크기
					// 30프로만 키워봐」라고 해서 30% 올린 2.6 이다. 피해 35 · 주기 240 ·
					// 되짚기 40 · 띄우기 4.0 · 1곳은 건드리지 않았다 — 사람이 말한 것은
					// 크기 하나뿐이고 「데미지는 충분해」라고 따로 못 박았다.
					//
					// 이 카드는 Aim.TRAIL 이라 반경을 키워도 겹침 금지 자리 찾기
					// (TrialRisks.reserveSpots)와는 무관하다. 그쪽을 지나는 것은 RANDOM_SPOT
					// 뿐이다. 고리 점 수도 안 변한다 — TrialWarning.ringPoints 의 하한이 40
					// 이라 반경 2.0 도 2.6 도 둘레가 그 하한에 못 미쳐 똑같이 40점이다.
					new Risk.DelayedStrike(Risk.Aim.TRAIL, Risk.Impact.EXPLOSION,
							240, 40, 35.0F, 2.6, 4.0, 1)),
			new Trial("sharedfate:pillar_fireball", "기둥 화염구",
					"10초마다 가장 가까운 흑요석 기둥에서 불덩이가 날아옵니다. 2초 동안 궤적이 보입니다.",
					POOL_ENTRY,
					// 실제로 맞아 보고 정한 값이다. 궤적 100틱(5초)은 「걸어서 비키면 되는」 속도라
					// 위협이 아니었고 피해 6 은 「아예 안 아픈」 값이었다. 궤적을 40틱으로 줄여
					// 속도를 2.5배로, 반경을 45% 넓히고, 피해를 14 로 올렸다. 그 14 도 무장
					// 기준으로는 4.1 이라 「3대에 죽는다」에 한참 못 미쳐 23 으로 다시 올렸다.
					//
					// 23 인 것은 이 카드에 난이도 곱이 걸리기 때문이다. TrialFireball 이
					// damageSources().explosion(null, null) 을 쓰는데 explosion 의 scaling 은
					// always 라 하드에서 먼저 1.5배가 된다. 23 × 1.5 = 34.5 가 방어·보호를 지나
					// 6.77 이고 세 대면 20.31 — 세 대째에 죽는다. 정확한 해는 22.79 다.
					// ⚠ 맨몸이면 34.5 가 그대로라 한 대에 전멸이다(클래스 설명을 볼 것).
					//
					// 궤적 40틱이 예고의 전부다. 이 카드가 요구하는 행동은 「제자리에서 옆으로
					// 비키기」뿐이므로 기준은 TrialWarning.TICKS_SIDESTEP(30틱)이고 40 은 그보다
					// 길다. 여기를 30 아래로 내리면 예고가 사후 통보가 된다.
					new Risk.TracedProjectile(200, 40, 23.0F, 4.35, 1)),
			new Trial("sharedfate:lightning_storm", "낙뢰",
					"6초마다 아레나 열 곳에 번개가 떨어집니다. 떨어지기 전에 자리가 보입니다.",
					POOL_ALL_CRYSTALS,
					// 실제로 맞아 보고 정한 값이다. 두 곳은 「비켜야 할 이유」가 거의 없었고 피해 5 는
					// 약했다. 열 곳으로 늘리고 피해를 12 로 올렸는데, 그러고도 「아직 안 아픈 정도」라
					// 하여 18 로 올렸다. 그 18 도 맨몸 날값이었고, 완전무장하면 2.5 라 여전히
					// 「안 아픈」 값이었다.
					//
					// 35 인 근거는 「자리 폭격」과 같다. 이 카드도 피해원이
					// damageSources().lightningBolt() 라 하드 곱이 안 걸리고, 무장 기준 6.93 ·
					// 세 대에 20.79 다. 역산은 34.18 이지만 내림하면 세 대에 19.83 으로
					// 살아남으므로 올림한 값이다 — 자세한 근거는 「자리 폭격」에 적어 두었다.
					// 두 카드가 같은 숫자인 것은 우연이 아니라 같은 피해원과 같은 「3대」
					// 기준에서 나온 결과다 — 한쪽을 고치면 다른 쪽도 함께 볼 것.
					//
					// 열 개짜리인데 35 를 쓸 수 있는 것은 겹침 규칙 하나 덕이다. TrialRisks 가
					// RANDOM_SPOT 전체에 「반경의 두 배보다 멀리」를 강제해 한 사람이 한 볼리에 한
					// 발만 맞게 만든다. 그 규칙을 지우면 무장 기준으로도 13.9 라 두 발에 팀
					// 체력의 7할이 날아가고, 맨몸이면 70 이다.
					//
					// ⚠ 맨몸이면 35 가 그대로 들어가 한 대에 전멸이다(클래스 설명을 볼 것).
					new Risk.DelayedStrike(Risk.Aim.RANDOM_SPOT, Risk.Impact.LIGHTNING,
							120, 0, 35.0F, 3.0, 0.0, 10)),
			new Trial("sharedfate:crystal_ward", "크리스탈 보호막",
					"남은 크리스탈이 화살에 맞지 않습니다. 올라가서 깨야 합니다.",
					POOL_FIRST_CRYSTAL,
					new Risk.CrystalGuard(true, false, false)),
			new Trial("sharedfate:iron_cage", "쇠창살과 무딘 곡괭이",
					"모든 크리스탈에 쇠창살이 다시 생기고 팀 전원의 채굴 속도가 15% 느려집니다.",
					POOL_FIRST_CRYSTAL,
					// 「채굴 피로에 걸립니다」였다. 사람이 플레이해 보고 「무딘곡괭이 이거
					// 채굴피로1은 심하고 채굴 속도 15프로감소로」라고 해서 바꿨다.
					//
					// 채굴 피로 I 은 바닐라에서 0.3^(등급+1) 이라 등급 0 에서도 채굴 속도를
					// 70% 깎는다(26.3 Player.getDestroySpeed 의 바이트코드에서 읽었다).
					// 15% 는 그 4분의 1도 안 되는 값이고, 그런 배율을 내는 바닐라 상태이상은
					// 없다 — 그래서 실행기가 getDestroySpeed 의 결과에 0.85 를 직접 곱한다.
					// 값과 그 길은 TrialCrystalGuard.DIG_SLOWDOWN_MULTIPLIER 에 있다.
					//
					// ⚠ 상태이상이 아니게 되었으므로 화면에 아이콘이 뜨지 않는다. 카드 이름과
					// 이 설명이 「곡괭이가 무뎌졌다」를 말하는 유일한 자리다.
					new Risk.CrystalGuard(false, true, true)),
			new Trial("sharedfate:dragon_mark", "표적",
					"크리스탈을 깬 사람이 10초 동안 표적이 되고 드래곤이 구체를 5발 날립니다. 그다음 20초는 쉽니다.",
					POOL_FIRST_CRYSTAL,
					// 10초 표적 · 20초 휴식 · 5발 · 발당 6. 다 맞으면 30 이라 팀 체력 20 을 넘지만
					// 발 사이가 2초씩 벌어져 있고 착탄 자리가 발사 시점에 얼어붙으므로, 전부 맞는
					// 것은 「가만히 서 있었다」는 뜻이다. 한 틱에 들어오는 것은 언제나 한 발 6 이다 —
					// 구체가 날아가는 시간이 발사 간격을 넘지 않아 두 발이 같은 틱에 닿지 않는다.
					//
					// ⚠ 이 카드만 방어도를 지나간다 — 적힌 6 은 다른 카드의 6 과 뜻이 다르다.
					// TrialDragonFocus 가 damageSources().magic() 을 쓰는데 magic 은
					// #bypasses_armor 태그에 들어 있어 방어도가 하나도 안 듣는다(보호 인챈트는
					// 듣고, 가해자가 없어 하드 곱은 안 걸린다). 그래서 무장 기준 한 발이 2.16 이고
					// 다섯 발이면 10.8 — 팀 체력의 5할 4푼이다. 같은 6 이라도 explosion 쪽
					// 카드였다면 무장 기준 0.9 남짓이었을 값이다.
					//
					// 값은 그대로 둔다. 「작은 카드」 중 유일하게 방어를 무시한다는 사실만 적어 둔다 —
					// 이 카드를 다시 잡을 때 사람이 그것을 보고 정해야 한다.
					new Risk.DragonFocus(Risk.Focus.CRYSTAL_BREAKER, 200, 400, 5, 6.0F)),
			new Trial("sharedfate:crystal_revival", "부활",
					"모든 크리스탈이 되살아납니다. 드래곤이 중앙 상공에서 그것을 지켜봅니다.",
					POOL_HEALTH_50,
					new Risk.CrystalRevive(10, 100, 0.0F)),
			new Trial("sharedfate:ender_pulse", "엔더 파동",
					"20초마다 중앙에서 바닥 고리가 퍼집니다. 지나갈 때 땅을 딛고 있으면 잠시 묶입니다.",
					POOL_ENTRY,
					new Risk.EnderPulse(400, 80, 42.0, 60)),
			new Trial("sharedfate:hotbar_lock", "굳는 손",
					"30초마다 핫바 두 칸이 굳습니다. 굳은 칸의 물건은 휘두를 수도 쓸 수도 없습니다.",
					POOL_ENTRY,
					new Risk.HotbarLock(600, 2)),
			new Trial("sharedfate:crystal_link", "연결된 수정",
					"크리스탈을 부수면 가장 가까운 다른 크리스탈이 30초 동안 보호막을 두릅니다. 그동안은 어떤 피해도 통하지 않습니다.",
					POOL_FIRST_CRYSTAL,
					// 8초로 냈더니 순서를 바꿀 만큼의 시간이 아니었다. 30초면 다른 기둥에
					// 갔다 오는 시간이라, 이 카드가 말하는 「순서를 다시 짜라」가 성립한다.
					new Risk.CrystalLink(600)),
			new Trial("sharedfate:crystal_overcharge", "수정 과충전",
					"크리스탈 하나가 붉게 달아오릅니다. 15초 안에 부수지 못하면 한 명이 10초 동안 빔에 물려 초당 3씩 닳습니다.",
					POOL_FIRST_CRYSTAL,
					// 초당 1 로 냈더니 「딜이 너무 낮다」 — 다 맞아도 10 이라 크리스탈을
					// 우선할 이유가 안 됐다. 세 배로 올린다. 다 맞으면 30 이라 팀 체력 20 을
					// 넘지만, 이 카드의 대응은 회피가 아니라 「블록 뒤에 숨기」이고 숨으면
					// 그 초는 통째로 빠진다. 열 초를 한 번도 못 가리는 것은 안 가린 것이다.
					new Risk.CrystalOvercharge(300, 200, 400, 3.0F)),
			new Trial("sharedfate:ender_storm", "엔더폭풍",
					"가장자리에서 중앙으로 소용돌이 둘이 밀려옵니다. 닿으면 아프고 바깥으로 계속 밀려납니다.",
					POOL_ALL_CRYSTALS,
					// 배율 1.0 으로 안쪽으로 밀었더니 「한 번 밀쳐지면 끝」이었다 — 폭풍이 오는
					// 방향으로 떠밀려 들어가니 다음 틱에는 이미 폭풍 뒤였다. 세 배로 올리고
					// 방향을 바깥으로 뒤집어, 닿아 있는 동안 계속 떠밀리게 한다.
					// 섬 밖으로 나가지 않게 하는 것은 이제 실행기의 천장 하나뿐이다.
					new Risk.EnderStorm(2, 2.0, 2.0F, 3.0, 500)),
			new Trial("sharedfate:dry_world", "메마른 세계",
					"엔드의 물과 용암이 증발하고, 그 뒤로는 물·용암·서리눈을 놓을 수 없습니다.",
					POOL_ALL_CRYSTALS,
					new Risk.DryWorld()),
			new Trial("sharedfate:night_host", "밤의 군세",
					"주위 20블록 안의 엔더맨이 다섯 마리까지 20초 동안 적대가 되어 쳐다보지 않아도 달려옵니다.",
					POOL_HEALTH_50,
					// 「엔드의 엔더맨 전부」로 냈더니 실제로 해 보고 「너무 빡세다」가 나왔다 —
					// 엔드는 엔더맨이 끝없이 깔린 곳이라 「전부」가 사실상 무한이었고, 한 번에
					// 몇 마리가 붙는지를 카드가 정하지 못했다. 사람이 「플레이어 주위에 20블럭 +
					// 5마리 최대」로 좁혔다. 적대 시간 400틱(20초)은 그대로다.
					new Risk.NightHost(400, 20.0, 5)),
			new Trial("sharedfate:end_rain", "종말의 비",
					"30초 동안 아레나 곳곳에 표시가 떴다가 떨어집니다. 표시된 자리에 서 있으면 크게 다칩니다.",
					POOL_HEALTH_50,
					// 한 볼리 10~15곳으로 냈더니 실제로 해 보고 「종말의 비를 생성이 지금의
					// 3배여도 괜찮을 것 같다」가 나와 세 배인 30~45곳으로 올렸다. 그것도
					// 플레이해 보고 사람이 「개수를 2배로」라고 해서 지금은 60~90곳이다.
					// 지속·간격·예고·반경은 두 번 다 그대로다.
					//
					// 피해만 10 에서 23 으로 올렸다. 10 은 무장 기준으로 2.9 라 「표시 위에 서
					// 있어도 된다」는 값이었다. TrialEndRain 이 쓰는 피해원이
					// damageSources().explosion(null, null) 이라 하드에서 1.5배가 먼저 걸리므로,
					// 「3대에 죽는다」(한 대 6.67)를 맞추는 값이 23 이다 — 「기둥 화염구」와 같은
					// 숫자이고 같은 계산이다. 23 × 1.5 = 34.5 → 무장 6.77 → 세 대 20.31.
					// ⚠ 맨몸이면 34.5 가 그대로라 한 대에 전멸이다(클래스 설명을 볼 것).
					//
					// ⚠ 지점 수에 딸려 온 문제가 둘이었다. 값은 사람이 정한 것이므로 되돌리지
					// 말 것. 둘 다 실행기 쪽에서 풀렸고, 아래는 그 결과다.
					//
					// 1) 점 예산 — 풀렸다. 전에는 TrialWarning.markGround 를 불러 점 수를
					//    거기서 받았고, TrialWarning.ringPoints 의 하한이 40 이라 반경 2.5
					//    짜리 고리도 한 바퀴에 40점을 썼다. 이제 TrialEndRain 이 고리를
					//    직접 그리고 제 간격(MARK_POINT_GAP 0.9)을 쓰므로 한 바퀴가 18점이다.
					//    90곳이면 한 바퀴 1620점이고, 구체 90점을 뺀 예산 310 으로 나누면
					//    ⌈1620/310⌉ = 6틱(MARK_MAX_STRIDE 와 같다). 한 틱에 고리 270 + 구체
					//    90 = 360점이라 예산(400~440. TrialEnderPulse.MAX_POINTS_PER_TICK 과
					//    DragonFireBarrage.MARK_MAX_POINTS 의 설명) 안이다.
					//    ⚠ 90곳이 그 상한에 꼭 맞는다. 여기서 지점을 더 늘리거나 반경을
					//    키우면 상한(파티클 수명 8틱에서 온 값이라 못 올린다)에 걸려 예산을
					//    넘는다 — 그때는 MARK_POINT_GAP 을 벌리는 것 말고 길이 없다.
					// 2) 겹침 금지 자리 찾기 — 겹침을 걷어내서 없어졌다. 목록을 지날 때는
					//    90 을 불러도 평균 84.2곳밖에 안 섰고 같이 걸린 「낙뢰」가 9.4곳에서
					//    4.2곳으로 반토막 났다. 그것을 알리자 사람이 「서로 겹쳐도 되니까
					//    내가 말한 숫자로 해 줘」라고 정해, TrialEndRain 만 겹침 금지 목록
					//    밖으로 나갔다. 이제 90 을 부르면 90곳이 다 서고 「낙뢰」도 열 곳을
					//    도로 다 받는다.
					//    ⚠⚠ 대신 고리끼리 겹친다. 3겹이면 무장하고도 20.31 이라 전멸이고,
					//    3겹 이상 구역이 생기는 볼리가 99.995% 다(그 넓이는 아레나의 0.51%).
					//    이 판의 「즉사 메커닉 0개」를 깬 첫 카드이고 사람이 대가를 알고 고른
					//    값이다 — 되돌리지 말 것. 20만 판 시뮬레이션 표는 TrialEndRain
					//    클래스 설명에 있다.
					new Risk.EndRain(600, 40, 60, 60, 90, 30, 23.0F, 2.5)),
			new Trial("sharedfate:landing_shock", "착지 충격",
					"드래곤이 착지할 때마다 중앙에서 충격이 2초 간격으로 세 번 퍼집니다. 땅을 딛고 있으면 맞고 멀리 밀려납니다.",
					POOL_HEALTH_80,
					// 반경 15 · 넉백 8 · 고리 하나로 냈더니 실제로 해 보고 「파란고리가 총 3번
					// 생기고 2초간격으로, 넉백도 지금보다 2배는 줘도 좋을 것 같고 범위도 지금의
					// 5배는 더 퍼지게」가 나왔다. 반경 75 · 넉백 16 · 고리 3개 · 간격 40틱(2초)이
					// 그 값이다. 피해 4 는 그대로 — 고리가 셋이 됐으므로 여기를 올리면 한 번
					// 착지에 팀이 받는 합계가 세 배로 뛴다.
					//
					// 퍼지는 시간 150틱은 여기서 정했다. 옛 40틱은 반경 15 짜리 값(0.375칸/틱)
					// 이라 반경 75 에 그대로 쓰면 초당 37.5칸이다 — 판정 창이 5틱이므로 고리
					// 두께가 9.4칸이 되고, 보고 나서 뛸 수 있는 시간이 남지 않는다. 반대로 옛
					// 속도를 그대로 지키면 200틱인데, 그러면 고리 사이가 15칸으로 좁아지고 셋이
					// 함께 떠 있는 시간이 125틱이라 어느 고리를 보고 뛰는지가 안 읽힌다.
					//
					// 그래서 둘 사이에서 이 저장소가 이미 쓰는 속도에 맞췄다. 「엔더 파동」이
					// 42칸을 80틱에 퍼져 0.525칸/틱이고, 75 / 150 은 정확히 0.5칸/틱이다. 두
					// 카드는 판정·그리기를 TrialEnderPulse 의 같은 함수로 나눠 쓰므로 속도가
					// 같아야 사람이 「고리는 뛰면 피한다」를 한 번만 배운다. 판정 창 5틱에 고리
					// 두께가 2.5칸이라 그쪽(2.6칸)과 같은 두께로 보이고, 고리 사이는
					// 40틱 × 0.5 = 20칸이라 서로를 삼키지 않는다.
					new Risk.LandingShock(150, 75.0, 4.0F, 16.0, 3, 40)));

	private TrialCatalog() {
	}

	public static List<Trial> all() {
		return TRIALS;
	}

	public static @Nullable Trial byId(@Nullable String id) {
		if (id == null) {
			return null;
		}
		for (Trial trial : TRIALS) {
			if (trial.id().equals(id)) {
				return trial;
			}
		}
		return null;
	}

	/**
	 * 이 자리에서 뽑힐 수 있는 카드들. 이미 뽑힌 것은 빠진다.
	 *
	 * <p>비어 있으면 그 트리거는 아무것도 주지 않고 지나간다 — 풀이 마른 것을 오류로 다루지
	 * 않는다. 카드를 채워 가는 동안에는 빈 풀이 정상이다.
	 */
	public static List<Trial> offerable(@Nullable Trigger trigger,
			@Nullable Collection<String> alreadyChosen) {
		List<Trial> available = new ArrayList<>();
		if (trigger == null) {
			return available;
		}
		for (Trial trial : TRIALS) {
			if (!trial.pools().contains(trigger)) {
				continue;
			}
			if (alreadyChosen == null || !alreadyChosen.contains(trial.id())) {
				available.add(trial);
			}
		}
		return available;
	}
}

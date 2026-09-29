package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link TrialCatalog.Risk.EndRain} 실행기 — 종말의 비.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p><b>한 번만</b> 터진다. 30초({@code durationTicks}) 동안
 * 2~3초({@code minInterval}~{@code maxInterval})마다 여러 곳({@code minSpots}~{@code maxSpots})에
 * 표시가 뜨고, 1.5초({@code warnTicks}) 뒤에 착탄한다. 반경 2.5({@code radius}) ·
 * 피해 23({@code damage}).
 *
 * <p>볼리 하나가 열리면 이렇게 보인다.
 *
 * <ol>
 *   <li>바닥에 <b>보라색 고리</b>가 뜬다 — 「여기가 위험하다」</li>
 *   <li><b>동시에</b> 하늘에서 <b>보라색 구체</b>가 그 자리로 떨어지기 시작한다 — 「곧 온다」</li>
 *   <li>구체가 <b>땅에 닿는 그 틱에</b> 터진다</li>
 * </ol>
 *
 * <p>착탄 시각은 예전과 같다. 구체는 예고를 <b>다시 말하는</b> 것이지 예고를 바꾸는 것이
 * 아니다 — 높이가 남은 틱에 정비례하므로({@link #orbHeight}) 구체가 바닥에 닿는 틱과
 * {@code landsAt} 은 같을 수밖에 없다. 먼저 닿거나 늦게 닿으면 예고가 거짓말이 된다.
 *
 * <h2>진짜 투사체를 띄우지 않는다</h2>
 *
 * <p>바닐라 투사체는 블록을 부수거나 불을 붙이고, 파괴는 {@code mobGriefing} 게임룰에 묶여 있어
 * 우리만 끌 수 없다. 엔드 섬에 구멍이 하나 뚫리면 다음 전투부터 발판이 달라지고, 그 구멍은 이
 * 전투가 유일하게 금지한 「대응 불가 즉사」(공허 낙사)로 이어진다. 게다가 한 볼리에 수십 발이라
 * 엔티티로 띄우면 그것만으로 틱이 밀린다. {@link TrialFireball}(기둥 화염구)과
 * {@link DragonFireBarrage}(연쇄 포격)가 이미 푼 문제라 같은 답을 쓴다 — <b>날아오는 모습은
 * 파티클로 그리고 착탄은 직접 계산한다.</b>
 *
 * <h2>색 — 사람이 보라를 골랐고 규약의 보라와는 다른 보라다</h2>
 *
 * <p>사람이 플레이해 보고 「이펙트도 보라색으로 바꿔줘」라고 했다. 그런데
 * {@link TrialWarning.Colors} 의 규약에서 <b>보라({@code MARKED})는 「너 하나를 노린다」</b>이고
 * 「표적」({@link TrialDragonFocus})이 그 뜻으로 쓰고 있다. 이 카드의 뜻은 정반대에 가깝다 —
 * 아무도 노리지 않고 <b>자리를 노린다.</b> 그래도 사람이 정했으므로 보라로 간다.
 *
 * <p>대신 <b>「표적」의 보라와 눈으로 갈리게</b> 했다. 가른 것이 색 하나가 아니라 셋이다.
 *
 * <ul>
 *   <li><b>색상</b> — {@code MARKED} 는 {@code 0xB44AFF}(R180·G74·B255)로 파랑이 가장 센
 *       <b>연보라</b>이고 색상환에서 275° 다. 이 카드의 {@link #MARK_COLOR} 는 초록이 0 이고
 *       빨강과 파랑이 같아 <b>자홍</b>으로 읽히는 300° 다</li>
 *   <li><b>모양</b> — 「표적」은 <b>한 사람을 따라다니는</b> 고리 하나와 드래곤에서 그 사람까지
 *       그은 선이다. 이쪽은 <b>아무도 따라오지 않는</b> 고리 수십 개가 아레나에 흩어져 한꺼번에
 *       뜬다. 자리가 얼어붙는 것이 이 카드의 회피법 자체다</li>
 *   <li><b>입자 종류</b> — 「표적」의 구체는 <b>먼지</b>({@code DustParticleOptions})라 납작한
 *       단색 사각형이다. 이쪽 구체는 {@code WITCH} 라 <b>애니메이션되는 반투명 별</b>이다.
 *       한 화면에 둘이 같이 떠도 재질부터 다르다</li>
 * </ul>
 *
 * <p>⚠ <b>{@link TrialWarning.Colors} 에 색을 더하지 않았다.</b> 그 넷은 <b>먼지 고리의
 * 규약</b>이고 카드 전부가 함께 쓰는 언어다. 여기에 다섯째를 더하면 그 순간 규약이 규약이
 * 아니게 되므로({@code Colors} 의 설명) <b>색을 이 실행기 안에 둔다</b> — 새 카드를 만드는
 * 사람이 이 색을 물려받지 않게 하는 것이 요점이다.
 *
 * <h2>입자 수명 — 30틱 예고에 60틱짜리를 쓰면 안 된다</h2>
 *
 * <p>엔더 계열 입자는 수명이 길다. 26.3 클라이언트를 뜯어 잰 값이다.
 *
 * <ul>
 *   <li>{@code PortalParticle}({@code PORTAL}) — {@code 40 + (int)(nextFloat() * 10)} 이라
 *       <b>40~49틱.</b> 게다가 {@code getQuadSize} 가 나이에 따라 0 에서 자라므로 <b>터진
 *       뒤에 가장 크다</b></li>
 *   <li>{@code ReversePortalParticle}({@code REVERSE_PORTAL}) — {@code 60 + (int)(nextFloat()*2)}
 *       라 <b>60~61틱</b></li>
 *   <li>{@code EndRodParticle}({@code END_ROD}) — {@code 60 + nextInt(12)} 라 <b>60~71틱</b></li>
 * </ul>
 *
 * <p>예고가 30틱인데 40~71틱짜리를 쓰면 <b>터진 뒤에도 자국이 남아 이미 안전한 자리가
 * 위험해 보인다.</b> 「터진 자리는 즉시 안전」은 이 전투가 플레이어와 맺은 약속이고
 * ({@link DragonFireBarrage} 의 「잔류를 남기지 않는 것이 이 패턴의 회피법을 만든다」),
 * 볼리 간격이 40~60틱이라 <b>지난 볼리의 자국이 다음 볼리의 고리와 겹친다.</b>
 *
 * <p>그래서 쓰는 것이 둘 다 <b>수명 8~40틱</b>짜리다. 우연이 아니라 26.3 의 두 클래스가
 * 같은 식을 쓴다 — {@code (int)(8.0 / (굴림 × 0.8 + 0.2))}.
 *
 * <ul>
 *   <li>고리 — {@code DustParticleBase}. 중력이 없어 <b>제자리에 그대로 선다</b>. 경계를
 *       가리키는 선이라 흔들리면 안 된다</li>
 *   <li>구체 — {@code SpellParticle}({@code WITCH}). 26.3 의 {@code WitchProvider} 가
 *       {@code setColor(f, 0, f)}, {@code f ∈ [0.35, 0.85)} 로 <b>순수 자홍</b>을 준다.
 *       중력이 {@code -0.1} 이라 아주 천천히 떠올라, 떨어지는 구체 뒤로 꼬리가 들린다</li>
 * </ul>
 *
 * <p>수명 하한이 둘 다 8틱이라 {@link #MARK_MAX_STRIDE} 의 근거가 그대로 살아 있다.
 *
 * <h2>소리 — {@code DRAGON_FIREBALL_EXPLODE} 는 <b>이름만</b> 드래곤이었다</h2>
 *
 * <p>사람이 「터지는 소리는 일반적인 폭발음이 아니라 엔더쪽 폭발음을 원해」라고 했다. 이 카드는
 * 그때 이미 {@code SoundEvents.DRAGON_FIREBALL_EXPLODE} 를 쓰고 있었는데, 바닐라
 * {@code sounds.json} 을 열어 보면 그 이름이 가리키는 파일이
 * {@code random/explode1~4} 로 <b>{@code entity.generic.explode} 와 글자 하나까지 같다.</b>
 * 자막 키도 {@code subtitles.entity.generic.explode} 다. 사람이 「일반적인 폭발음」이라고 한
 * 것이 정확했다 — <b>이름을 믿고 고른 소리였다.</b>
 *
 * <p>그래서 실제로 엔더 소리 파일을 쓰는 것으로 바꿨다. 고른 것이
 * {@link SoundEvents#ENDER_EYE_DEATH}({@code entity/endereye/dead1,2})다. 이 저장소에서 아직
 * 아무도 안 쓰는 유일한 엔더 계열 「깨지는」 소리라, 사람이 이미 배운 신호와 겹치지 않는다 —
 * {@code END_PORTAL_SPAWN} 은 「크리스탈 부활」, {@code SHULKER_BULLET_HIT} 은 「표적」,
 * {@code PORTAL_TRIGGER} 는 「엔더 폭풍」, {@code ENDERMAN_TELEPORT} 는 「엔더 파동」,
 * {@code ENDER_DRAGON_GROWL} 은 경고 첫 층이 쓰고 있다.
 *
 * <p>⚠ <b>이름으로 고르지 말고 {@code sounds.json} 을 열어 볼 것.</b> 이 카드가 그 함정에
 * 한 번 걸렸다.
 *
 * <h2>겹침 금지 규칙을 반드시 태울 것</h2>
 *
 * <p>지점을 직접 굴리지 말고 {@link TrialRisks} 의 <b>살아 있는 지점 목록</b>을 지나게 하라. 한
 * 볼리 안에서만 떨어뜨려 놓는 것으로는 모자란다 — 이 카드와 「낙뢰」(피해 18)는 자리가 달라도
 * <b>둘 다 쌓여 동시에 돈다.</b> 두 고리가 겹친 자리에 선 사람은 한 틱에 둘을 다 받고, 팀 공유
 * 체력은 20 이다. 전멸은 곧 월드 삭제다.
 *
 * <h2>지점은 {@link TrialRisks#reserveSpots} 로만 잡고 터진 그 틱에 돌려준다</h2>
 *
 * <p>이 파일은 좌표를 스스로 굴리지 않는다. {@code reserveSpots} 는 <b>살아 있는 모든 지점</b>
 * (다른 카드의 것 포함)에서 두 반경의 합보다 멀리 떨어진 자리만 내주므로, 그 길을 지나는 것이
 * 「낙뢰 18 + 종말의 비 23」을 막는 유일한 장치다.
 *
 * <p>그리고 <b>고리가 터진 그 틱에 {@link TrialRisks#releaseSpots} 로 놓는다.</b> 이 카드의
 * 고리는 예고 30틱 동안만 바닥에 있고 착탄과 함께 사라지므로, 그 뒤로도 자리를 붙들고 있으면
 * 이미 아무것도 없는 곳을 다른 카드가 영영 못 쓰게 된다. {@code LIVE_SPOTS} 는 「지금 바닥에
 * <b>살아 있는</b> 지점」의 목록이지 「이 카드가 쓴 적 있는 자리」의 목록이 아니다.
 *
 * <p>⚠ <b>열쇠는 {@code TrialRisks} 가 쓰는 것과 글자 하나까지 같아야 한다.</b> 분배기가 매 틱
 * {@code LIVE_SPOTS.keySet().retainAll(살아 있는 카드의 열쇠)} 로 없어진 카드의 자리를 놓는데,
 * 다른 모양의 열쇠를 쓰면 <b>잡자마자 매 틱 지워진다</b> — 우리는 남의 고리를 피하는데 남은 우리
 * 고리를 못 보는, 한쪽만 새는 상태가 된다.
 *
 * <p>그래서 <b>열쇠를 만들지 않고 분배기에게 받는다</b>({@link #tick} 의 {@code key}). 한때는
 * 진입점이 열쇠를 안 받아 {@code TrialCatalog.all()} 에서 값으로 되찾는 우회로를 두었는데,
 * 그러면 값이 완전히 같은 위험을 카드 둘에 걸었을 때 앞 카드의 열쇠가 나와 뒤 카드가 겹침
 * 검사에서 빠진다. <b>열쇠를 두 곳에서 만들면 언젠가 갈라진다</b> — 되돌리지 말 것.
 *
 * <h2>⚠ 지점을 늘려도 자리가 그만큼 서지는 않는다 — 그리고 「낙뢰」가 그 값을 치른다</h2>
 *
 * <p>{@code reserveSpots} 는 <b>적힌 것보다 적게 내줄 수 있다.</b> 겹치느니 한 발 빠지는 것이
 * 이 저장소의 규칙이라 그 자체는 오류가 아니다. 다만 <b>얼마나 빠지는지</b>는 알고 값을 정해야
 * 한다. 반경 40 아레나 · 반경 2.5 · 최소 간격 5칸 · {@code SPOT_TRIES} 8 로 20만 판을 굴린
 * 값이다(「낙뢰」는 반경 3 짜리 열 곳이고, 이 카드가 <b>먼저</b> 잡은 뒤 부른 경우다).
 *
 * <table border="1">
 *   <caption>20만 판 시뮬레이션</caption>
 *   <tr><th>부른 수</th><th>실제로 선 수(평균)</th><th>최소</th><th>다 서는 판</th>
 *       <th>그 뒤 「낙뢰」가 받는 수</th></tr>
 *   <tr><td>45</td><td>44.94</td><td>42</td><td>94.4%</td><td>9.44 / 10</td></tr>
 *   <tr><td>60</td><td>59.52</td><td>55</td><td>60.8%</td><td>8.11 / 10</td></tr>
 *   <tr><td>75(60~90 의 가운데)</td><td>72.91</td><td>66</td><td>9.6%</td><td>6.11 / 10</td></tr>
 *   <tr><td>90</td><td><b>84.18</b></td><td>74</td><td>0.06%</td><td><b>4.19 / 10</b></td></tr>
 * </table>
 *
 * <p>읽는 법이 둘이다.
 *
 * <ul>
 *   <li><b>이 카드는 거의 두 배가 된다.</b> 45 → 90 을 부르면 44.94 → 84.18 이라 1.87배다.
 *       사람이 정한 두 배가 실제로도 거의 두 배로 일어난다</li>
 *   <li>⚠ <b>대신 「낙뢰」가 반토막 난다.</b> 9.44 → 4.19 다. 0.3% 의 판에서는 <b>한 곳도 못
 *       받는다.</b> 반대로 「낙뢰」의 주기가 먼저 온 틱이면 「낙뢰」가 열 곳을 다 가져가고 이
 *       카드가 84.18 → 79.20 으로 줄어든다. <b>겹치느니 빠진다가 이 저장소의 규칙이라 그대로
 *       두지만, 낙뢰가 절반으로 준 것은 이 카드가 만든 일이다</b> — 낙뢰의 피해가 약해 보이면
 *       낙뢰 값을 의심하기 전에 여기를 볼 것</li>
 * </ul>
 *
 * <h2>예고 30틱은 「제자리에서 옆으로 비키기」의 하한이다</h2>
 *
 * <p>{@link TrialWarning#TICKS_SIDESTEP} 이 정확히 30틱이고 이 카드의 {@code warnTicks} 가 그
 * 값이다. 이 카드가 요구하는 행동이 딱 그것이기 때문이다 — <b>자리는 볼리가 열리는 순간
 * 얼어붙고</b>({@code reserveSpots} 가 내준 좌표를 착탄까지 그대로 들고 간다) 사람을 따라오지
 * 않으므로, 사람은 갈 곳을 고를 것도 넷이 합의할 것도 없이 고리 밖으로 한 걸음 나가기만 하면
 * 된다. 여기를 30 아래로 내리면 예고가 아니라 <b>사후 통보</b>다.
 *
 * <h2>블록을 부수지 않는다 · 넉백을 주지 않는다</h2>
 *
 * <p>피해원에 실체를 달지 않는 것이 <b>넉백을 막는 장치</b>다. {@code LivingEntity} 는 피해원에
 * 엔티티가 있을 때만 밀어내는데, 엔드 섬 가장자리에서 밀리면 허공이고 공유 체력이라 한 사람의
 * 낙사가 팀 전체를 끝낸다.
 *
 * <h2>고리 하나는 팀에 한 번만</h2>
 *
 * <p>공유 체력에서 범위 피해는 <b>팀원별로 합산</b>된다. 고리 하나에 넷이 서 있을 때 넷을 다
 * 때리면 92 고 팀 체력은 20 이다 — 넷이 함께 움직이는 것이 이 게임의 올바른 대응인데 그러면
 * <b>올바른 대응이 전멸</b>이 된다. 「연쇄 포격」이 같은 이유로 원 하나에 한 번만 넣는다
 * ({@link DragonFireBarrage} 의 「팀에게 한 번만」).
 */
public final class TrialEndRain {

	/**
	 * 이 카드가 <b>예고 한 틱에</b> 쓸 수 있는 점 수. 고리와 구체를 <b>합쳐</b>서다.
	 *
	 * <p>400 은 이 저장소가 쓰는 한 틱 예산이다 — 「낙뢰」가 반경 3 짜리 고리 열 개로 쓰는 값이고
	 * ({@code TrialEnderPulse.MAX_POINTS_PER_TICK} · {@code TrialEnderStorm.MAX_POINTS_PER_TICK}
	 * 도 같은 400 이다), 그 값을 여기서도 그대로 쓴다.
	 *
	 * <p>⚠ <b>구체가 생기면서 「고리의 예산」이 아니라 「이 카드의 예산」이 됐다.</b> 구체가 먼저
	 * 제 몫({@link #orbPoints})을 떼어 가고 남은 것으로 고리를 나눠 그린다({@link #markStride}) —
	 * 그래야 지점 수를 올리는 사람이 둘을 따로 세지 않는다. 실제 값은 {@link #tickPoints} 가
	 * 돌려주고 {@code TrialEndRainTest} 가 본다.
	 *
	 * <p>이 값은 <b>이 카드 혼자</b>의 몫이다. 시련은 전투가 끝날 때까지 쌓이므로 같은 틱에 남의
	 * 고리도 함께 그려진다는 것을 잊지 말 것.
	 */
	static final int MARK_BUDGET = 400;
	/**
	 * 착탄하는 <b>그 한 틱</b>의 점 예산.
	 *
	 * <p>예고 예산의 두 배를 준다. 착탄 틱에는 <b>고리도 구체도 그리지 않기</b> 때문이다 —
	 * {@link #land} 가 볼리를 비우므로 {@link #warn} 이 그릴 것이 없다. 한 틱뿐이고 그 틱에
	 * 다른 몫이 없으니 두 배까지는 받아들인다.
	 *
	 * <p>이 값이 생기기 전에는 지점마다 25점(폭발 1 + {@code REVERSE_PORTAL} 24)을 쐈다. 45곳이면
	 * 이미 <b>1125점</b>이고 90곳이면 2250점이라, 예산이 없던 쪽이 훨씬 컸다.
	 */
	static final int IMPACT_BUDGET = MARK_BUDGET * 2;
	/**
	 * 고리 한 바퀴를 나눠 그릴 수 있는 <b>최대 틱 수</b>.
	 *
	 * <p>고리는 {@link TrialWarning#dust} 가 만드는 먼지 파티클이고 그 수명이 <b>최소 8틱</b>이다
	 * (26.3 {@code DustParticleBase} 의 생성자 —
	 * {@code max(1, (int)(8.0 / (nextDouble() * 0.8 + 0.2)) * scale)}, 우리는 {@code scale} 이
	 * 1.0 이다). 한 바퀴를 8틱 이상에 걸쳐 그리면 마지막 점을 찍기 전에 첫 점이 죽어
	 * <b>고리가 영영 안 닫힌다.</b> 6 은 그 8 에서 두 틱을 뺀 자리다.
	 *
	 * <p>⚠ <b>고리를 다른 입자로 바꾸면 이 값의 근거가 함께 바뀐다.</b> 구체가 쓰는
	 * {@code SpellParticle} 이 마침 같은 식({@code 8.0 / (굴림 × 0.8 + 0.2)})이라 하한도 8틱으로
	 * 같지만, {@code PORTAL}(40~49) · {@code REVERSE_PORTAL}(60~61) · {@code END_ROD}(60~71)로
	 * 바꾸면 하한이 열 배가 된다 — 그쪽은 나눠 그리기가 아니라 <b>잔류</b>가 먼저 문제다
	 * (클래스 설명의 「입자 수명」).
	 */
	static final int MARK_MAX_STRIDE = 6;

	/**
	 * 바닥 고리와 구체의 보라.
	 *
	 * <p>{@code WITCH} 입자가 26.3 에서 {@code setColor(f, 0, f)}({@code f ∈ [0.35, 0.85)})로
	 * 내는 <b>순수 자홍</b>에 맞춘 값이다. 하늘에서 떨어지는 것과 바닥에 뜬 것이 같은 색이라야
	 * 「저 구체가 이 고리로 온다」가 읽힌다.
	 *
	 * <p>⚠ <b>{@link TrialWarning.Colors#MARKED}(0xB44AFF)를 그대로 쓰지 말 것.</b> 그것은
	 * 「너 하나를 노린다」의 보라이고 「표적」이 쓰고 있다. 클래스 설명의 「색」에 무엇으로
	 * 갈랐는지 적어 두었다 — 색상환에서 275° 대 300°, 그리고 모양과 입자 종류다.
	 *
	 * <p>⚠ <b>이 색을 {@code TrialWarning.Colors} 로 옮기지 말 것.</b> 거기 넷은 카드 전부가
	 * 함께 쓰는 언어이고, 다섯째가 생기는 순간 규약이 규약이 아니게 된다. 여기 있어야 새 카드를
	 * 만드는 사람이 물려받지 않는다.
	 */
	static final int MARK_COLOR = 0xC800C8;
	/**
	 * 고리에서 이웃한 두 점 사이 간격(블록).
	 *
	 * <p>{@link TrialWarning#POINT_GAP}(0.5)을 쓰지 <b>않는다.</b> 반경 2.5 의 둘레가 15.71칸이라
	 * 0.5 면 32점이고, 90곳이면 나눠 그려도 한 틱에 {@code 90 × ⌈32/6⌉ = 540} 점이라 구체를 빼고도
	 * 예산 밖이다. 0.8 이면 20점이라 고리만 360점, 구체 90점을 더해 450점으로 여전히 넘는다.
	 * <b>0.9 는 고리 90개와 구체 90개를 400점 안에 다 넣을 수 있는 가장 촘촘한 값</b>이고,
	 * 반경 2.5 에서 18점 · 실제 간격 0.87칸이 된다({@link #ringGap}).
	 *
	 * <p>전에는 {@link TrialWarning#markGround} 를 불러 점 수를 거기서 받았는데, 그쪽 하한
	 * ({@code BASE_POINTS} 40)이 반경 2.5 에 <b>간격 0.39칸</b>이라 지나치게 촘촘하다는 것이
	 * 그때도 주석에 적혀 있었다 — 「한 카드의 예산 때문에 남의 연출을 바꾸지 않는다」고 손대지
	 * 못하고 있던 자리다. 고리를 이 파일에서 직접 그리면 <b>남의 고리를 하나도 건드리지 않고</b>
	 * 이 카드만 제 반경에 맞는 밀도를 쓸 수 있다.
	 */
	static final double MARK_POINT_GAP = 0.9;
	/**
	 * 반경이 아무리 작아도 고리에 찍는 점 수의 하한.
	 *
	 * <p>간격만 정하고 두면 반경 0.5 짜리 고리가 네 점, 곧 마름모가 된다. 12점이면 어떤 반경에서도
	 * 원으로 읽힌다.
	 */
	static final int MARK_MIN_POINTS = 12;
	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다({@code TrialWarning} 과 같은 값). */
	private static final double GROUND_OFFSET = 0.15;

	/**
	 * 구체가 <b>떨어지기 시작하는 높이</b>(블록). 그 자리의 지표에서 잰다.
	 *
	 * <h2>20 인 근거</h2>
	 *
	 * <p>두 가지가 서로 반대로 당긴다.
	 *
	 * <ul>
	 *   <li><b>너무 높으면 화면 밖이다.</b> 기본 시야각 70°는 <i>가로</i> 값이라 16:9 에서 세로는
	 *       {@code 2·atan(tan(35°)·9/16) ≈ 43°}, 곧 정면을 볼 때 위로 <b>21.5°</b>까지만 보인다.
	 *       20칸 위에 뜬 것이 그 안에 들어오려면 <b>51칸 밖</b>이어야 하고, 절반쯤 내려온 10칸
	 *       높이면 <b>25칸 밖</b>이면 된다. 아레나가 지름 80칸이라 한 사람에게 대부분의 구체는
	 *       멀리 있고, <b>고개를 들지 않아도 하늘에서 비가 오는 것으로 보인다.</b> 40칸으로
	 *       올리면 그 거리가 두 배가 되어 가까운 구체는 고개를 들어야만 보인다</li>
	 *   <li><b>너무 낮으면 「하늘에서」가 안 읽힌다.</b> 12칸 아래면 구체가 예고 내내 눈높이 띠
	 *       안에 머물러 <b>옆에서 날아온 것</b>과 구별되지 않는다</li>
	 * </ul>
	 *
	 * <p>여기에 <b>꼬리 길이</b>가 더해진다. 예고가 30틱이므로 20칸은 틱당 0.67칸이고,
	 * {@code SpellParticle} 의 수명 하한이 8틱이라 꼬리가 <b>최소 5.3칸</b>이다 — 구체 지름의
	 * 두어 배라 「꼬리를 단 구체」로 읽힌다. 40칸으로 올리면 틱당 1.33칸이라 같은 입자가
	 * <b>10.7칸짜리 기둥</b>을 그려 구체가 아니라 빔이 된다.
	 */
	static final double ORB_DROP_HEIGHT = 20.0;
	/**
	 * 구체 하나가 <b>한 틱에</b> 쓰는 점 수.
	 *
	 * <p>1 이다. 90곳이면 이것만으로 이미 90점이고, 2 로 올리면 180점이라 고리에 남는 몫이
	 * 220점으로 줄어 {@link #markStride} 가 상한(6)에 걸린다 — 그러면 한 틱 450점으로 예산을
	 * 넘는다. <b>구체 하나에 한 점</b>이 90개를 띄우면서 예산을 지키는 유일한 값이다.
	 *
	 * <p>점 하나로 구체가 되는 것은 <b>꼬리 덕분</b>이다. 입자가 8~40틱 살아 있으므로 매 틱 한
	 * 점씩만 찍어도 화면에는 열 점 안팎이 세로로 늘어서고, 가장 아래(가장 새 점)가 구체의
	 * 머리로 읽힌다.
	 */
	static final int ORB_POINTS_PER_SPOT = 1;
	/**
	 * 구체 점을 흩뿌리는 폭(블록).
	 *
	 * <p>0 이면 점이 한 줄로 정확히 찍혀 <b>자로 그은 선</b>이 된다. 조금 흔들어야 떨어지는
	 * 덩어리로 보인다.
	 */
	private static final double ORB_SPREAD = 0.12;
	/**
	 * 착탄 한 자리에 쓰는 점 수의 <b>상한</b>.
	 *
	 * <p>{@link #IMPACT_BUDGET} 을 지점 수로 나눈 값이 이보다 커도 여기서 멈춘다. 반경 2.5 안에
	 * 열두 점을 넘겨 봐야 덩어리 하나로 뭉쳐 보이는 것이 달라지지 않는다.
	 */
	static final int IMPACT_MAX_POINTS = 12;

	/**
	 * 지금 내리고 있는 비. 열쇠는 분배기가 넘겨주는 위험 열쇠({@code 카드 id + '#' + 순번})다.
	 *
	 * <p>정적 맵인 이유는 위험이 값(레코드)이라 상태를 들 수 없기 때문이다. 월드가 바뀌면 남은
	 * 좌표가 새 판에서 터지므로 {@link #clearState()} 로 반드시 비운다.
	 *
	 * <p>칸 하나가 아니라 맵인 것은 <b>「한 번만 터진다」를 기억하는 자리가 여기</b>이기
	 * 때문이다. 자세한 것은 {@link #tick} 에 적어 두었다.
	 */
	private static final Map<String, Downpour> RAINS = new HashMap<>();

	/**
	 * 바닥에 떠 있는 한 볼리.
	 *
	 * <p>{@code spots} 가 좌표인 것이 핵심이다. 사람을 들고 있으면 매 틱 그 사람의 현재 자리를
	 * 읽게 되고, 그 순간 표식이 사람을 쫓아다녀 <b>비킬 수 없는 카드</b>가 된다.
	 *
	 * <p>⚠ <b>좌표의 {@code y} 가 그 자리의 실제 지표다.</b> {@link TrialRisks#reserveSpots} 가
	 * 자리를 내줄 때 이미 {@code getHeightmapPos(MOTION_BLOCKING_NO_LEAVES, ...)} 로 그 칸의
	 * 설 수 있는 높이를 물어 담아 준다 — {@code TrialEnderPulse.Ground} 가 점마다 하는 계산과
	 * 같은 값이다. 그래서 <b>구체가 떨어질 바닥을 여기서 다시 묻지 않는다</b>: 볼리가 열린
	 * 틱의 지표를 착탄까지 그대로 들고 가고, 자리마다 높이가 달라도 구체는 제 자리의 땅에 닿는다.
	 *
	 * @param spots   이번 볼리의 고리 중심들. {@link TrialRisks#reserveSpots} 가 내준 자리뿐이다
	 * @param landsAt 착탄하는 틱. 볼리가 열린 틱 + {@code warnTicks} 다
	 */
	private record Volley(List<Vec3> spots, long landsAt) {

		Volley {
			spots = List.copyOf(spots);
		}
	}

	/**
	 * 한 번뿐인 비 전체.
	 *
	 * @param granted      카드를 받은 틱. 이 값이 바뀌면 다른 판에서 받은 카드다
	 * @param nextVolleyAt 다음 볼리를 여는 틱. 볼리마다 새로 굴린다
	 * @param volley       지금 바닥에 떠 있는 볼리. 착탄과 예고 사이가 아니면 {@code null}
	 */
	private record Downpour(long granted, long nextVolleyAt, @Nullable Volley volley) {

		Downpour without() {
			return new Downpour(granted, nextVolleyAt, null);
		}
	}

	private TrialEndRain() {
	}

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자도 받는 것은 {@link TrialRisks} 의
	 * 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <h2>「한 번만 터진다」를 무엇으로 기억하는가</h2>
	 *
	 * <p>따로 센 횟수를 들지 않는다. <b>받은 틱과 지금 틱의 차이</b>가 전부다 —
	 * {@code now - granted} 가 {@code durationTicks} 를 넘으면 그 뒤로는 영영 아무 일도 하지
	 * 않는다. 그렇게 둔 이유가 둘이다.
	 *
	 * <ul>
	 *   <li><b>되감기와 복원에 강하다.</b> 세션은 저장 파일에서 오고 게임 시각은 월드에서 오므로
	 *       서버를 껐다 켜면 위상이 튄다. 횟수를 세어 두면 그때 한 번 더 쏟아질 수 있지만, 받은
	 *       틱으로 재면 <b>몇 번을 물어도 같은 답</b>이다</li>
	 *   <li><b>비울 것이 없다.</b> 끝났다는 사실이 값에서 나오므로 누가 깃발을 내려 주기를
	 *       기다리지 않는다 — 배선을 한 줄 빠뜨려 「어느 판에서만 두 번 온다」가 되는 길이 없다</li>
	 * </ul>
	 *
	 * <p>{@link #RAINS} 의 칸은 「끝났는가」가 아니라 <b>「아직 돌려주지 않은 자리가 있는가」</b>를
	 * 들고 있다. 그래서 끝나는 틱에 정확히 한 번 {@link TrialRisks#releaseSpots} 가 불린다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱. 비가 내리는 시간은 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.EndRain risk) {
		if (end == null || members == null || members.isEmpty() || risk == null || !usable(risk)
				|| key == null) {
			return;
		}

		Downpour run = RAINS.get(key);
		if (run != null && run.granted() != granted) {
			// 다른 판에서 받은 카드의 찌꺼기다. 자리를 돌려주고 처음부터 센다.
			finish(key);
			run = null;
		}

		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		boolean raining = raining(elapsed, risk);
		if (run == null) {
			if (!raining) {
				// 받은 바로 그 틱이거나, 30초가 이미 끝났다. 끝난 쪽은 영영 여기서 되돌아간다.
				return;
			}
			// 받자마자 떨어뜨리지 않는다. 카드를 받은 틱을 0번째 볼리로 보고 거기서 한 간격을
			// 굴려 첫 볼리를 연다 — 주기를 now % interval 이 아니라 granted 에서 재는 것과 같은
			// 이유다. 카드마다 위상이 저절로 어긋나고, 설명을 읽는 중에 맞지 않는다.
			run = new Downpour(granted, now + rolledInterval(end.getRandom(), risk), null);
		}

		// 순서가 셋이다 — 터뜨리고, 새 볼리를 열고, 떠 있는 것을 그린다. 터뜨리는 것이 먼저라야
		// 방금 터진 고리가 그 틱에 사라지고, 그리는 것이 마지막이라야 이번 틱에 열린 볼리가
		// 첫 틱부터 고리와 구체와 소리를 낸다.
		run = land(end, members, key, run, now, risk);
		if (raining) {
			run = openVolley(end, key, run, now, elapsed, risk);
		}
		warn(end, members, run, now, risk);

		if (run.volley() == null && !raining) {
			// 마지막 볼리까지 끝났다. 자리를 돌려주고 기록을 지운다 — 다음 틱부터는 위의
			// 「끝난 쪽은 영영 되돌아간다」로 빠진다.
			finish(key);
			return;
		}
		RAINS.put(key, run);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p><b>잡아 둔 지점을 {@link TrialRisks} 의 살아 있는 목록에서도 놓을 것.</b> 남겨 두면 다음
	 * 판의 다른 카드가 아레나 일부를 영영 쓰지 못한다 — 컴파일도 시험도 조용한 종류의 사고다.
	 *
	 * <p>{@code TrialRisks.clearState()} 는 제 목록을 먼저 비우고 여기를 부르므로 실제로는 지울
	 * 것이 없을 때가 많다. 그래도 놓는 것은 <b>부르는 순서에 기대지 않기 위해서</b>다 — 순서가
	 * 바뀌면 조용히 새는 쪽이 이 함수다.
	 */
	public static void clearState() {
		for (String key : RAINS.keySet()) {
			TrialRisks.releaseSpots(key);
		}
		RAINS.clear();
	}

	// ------------------------------------------------------------------ 볼리 한 번

	/**
	 * 착탄할 때가 됐으면 터뜨리고 자리를 돌려준다.
	 *
	 * <p>착탄하는 틱에는 고리도 구체도 다시 그리지 않는다 — 볼리를 여기서 비우므로 {@link #warn}
	 * 이 그릴 것이 없다. 같은 틱에 표식을 한 벌 더 보내면 방금 터진 고리가 한 틱 더 살아 있는
	 * 것으로 보이고, 「터진 자리는 즉시 안전」이 그 한 틱에서 먼저 깨진다 — 「연쇄 포격」이 같은
	 * 자리에서 같은 판단을 한다.
	 *
	 * <p>소리는 <b>지점마다가 아니라 사람마다</b> 한 번씩 낸다({@link #impactSound}).
	 */
	private static Downpour land(ServerLevel end, List<ServerPlayer> members, String key,
			Downpour run, long now, TrialCatalog.Risk.EndRain risk) {
		Volley volley = run.volley();
		if (volley == null || now < volley.landsAt()) {
			return run;
		}
		for (Vec3 spot : volley.spots()) {
			detonate(end, members, spot, risk);
		}
		impactSound(end, members);
		// 고리는 터진 그 틱에 사라진다. 살아 있지 않은 자리를 붙들고 있으면 다른 카드가
		// 아레나의 그만큼을 영영 못 쓴다.
		TrialRisks.releaseSpots(key);
		return run.without();
	}

	/**
	 * 새 볼리를 연다.
	 *
	 * <p>착탄이 30초 안에 들어오는 볼리만 연다. 예고가 끝나기 전에 카드가 끝나면 <b>그려 놓은
	 * 표식이 착탄 없이 사라지고</b>, 그것은 예고가 거짓말을 한 것이 된다 — 이 전투는 이미 보여 준
	 * 표식을 무르지 않는다.
	 *
	 * <p>{@link TrialRisks#reserveSpots} 가 <b>적힌 것보다 적게 내줄 수 있다.</b> 살아 있는 다른
	 * 고리들 때문에 자리를 못 찾은 것이고, 그건 정상이다 — 겹치느니 한 발 빠지는 쪽이다. 한 자리도
	 * 못 얻으면 이번 볼리는 통째로 건너뛰고 <b>다음 볼리 시각은 그대로 굴린다</b>. 매 틱 다시
	 * 시도하면 아레나가 붐빌 때 예고 없이 뜬금없는 틱에 열린다.
	 *
	 * <p>몇 곳이나 서는지는 클래스 설명의 표에 20만 판 시뮬레이션으로 적어 두었다. <b>90곳을
	 * 부르면 평균 84.18곳이 서고 그 대신 「낙뢰」가 9.44 → 4.19 로 반토막 난다</b> — 지점 수를
	 * 다시 만지는 사람은 그 표를 먼저 볼 것.
	 */
	private static Downpour openVolley(ServerLevel end, String key, Downpour run, long now,
			long elapsed, TrialCatalog.Risk.EndRain risk) {
		if (run.volley() != null || now < run.nextVolleyAt() || !landsInWindow(elapsed, risk)) {
			return run;
		}
		RandomSource random = end.getRandom();
		List<Vec3> spots = TrialRisks.reserveSpots(end, key, rolledSpots(random, risk),
				risk.radius());
		long next = now + rolledInterval(random, risk);
		if (spots.isEmpty()) {
			// 예약이 빈 자리를 남겨 두므로 열쇠를 지워 둔다. 「우리가 붙들고 있는 것이 없다」를
			// 목록에도 그대로 적어 두는 쪽이 다음 사람에게 읽기 쉽다.
			TrialRisks.releaseSpots(key);
			return new Downpour(run.granted(), next, null);
		}
		return new Downpour(run.granted(), next, new Volley(spots, now + risk.warnTicks()));
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 고리를 그리고, 구체를 한 칸 떨어뜨리고, 경고음을 올린다.
	 *
	 * <p>고리는 예고 내내 매 틱 손을 댄다. 표식이 남은 신호의 절반이고(자막은 걷어냈다) 사람이
	 * 자기 발밑을 보고 비키는 것이 이 카드의 전부라 성기게 둘 수 없다.
	 *
	 * <h2>한 바퀴를 한 틱에 다 그리지 않는다</h2>
	 *
	 * <p>점 하나가 패킷 한 장이고 예고 30틱 내내 나간다. 지점이 수십 곳이면 고리 전체를 매 틱
	 * 그리는 것은 이 카드 하나가 파티클만으로 틱을 미는 일이다. 줄일 수 있는 것이 셋인데 둘이
	 * 막혀 있다 — <b>지점 수</b>는 사람이 정한 값이고 <b>반경</b>은 피해 범위라 연출 사정으로 못
	 * 건드린다. 남은 것이 <b>「한 틱에 얼마나 그리는가」</b>라서 시간축으로 나눈다:
	 * {@link #markStride} 틱에 걸쳐 한 바퀴를 채우고, 그 사이 먼저 찍은 점은 아직 살아 있다
	 * ({@link #MARK_MAX_STRIDE} 에 근거).
	 *
	 * <p>고리가 <b>완전해지는 데 {@link #markStride} 틱이 걸린다.</b> 예고가 30틱이라 앞부분만
	 * 성기고 나머지는 온전한 고리다. 첫 틱에도 점은 한 바퀴에 고루 찍히므로 「어디인가」는 그
	 * 틱부터 읽힌다 — 비어 보이는 틱은 없다.
	 *
	 * <h2>구체는 나눠 그리지 않는다</h2>
	 *
	 * <p>고리는 <b>같은 자리에 머무는 모양</b>이라 여러 틱에 나눠 채워도 완성된다. 구체는
	 * <b>매 틱 자리가 바뀌므로</b> 건너뛴 틱은 채워지지 않고 그냥 빈다 — 나누면 구체가 깜박인다.
	 * 그래서 지점마다 매 틱 {@link #ORB_POINTS_PER_SPOT} 점을 쓰고, 그 몫을 고리 예산에서 먼저
	 * 떼어 낸다({@link #markStride}).
	 *
	 * <p>소리는 <b>사람마다 그 자리에서</b> 울린다. 바닐라 소리 사거리는 볼륨이 1 이하면 16칸이라
	 * 착탄 지점에서 울리면 반대편 사람에게 닿지 않는다. 「연쇄 포격」이 선 80칸 때문에 이미 같은
	 * 답을 쓴다.
	 *
	 * <p>볼리가 열리는 첫 틱에는 층이 바뀌지 않아도 울린다. 예고가 30틱뿐이라 남은 틱이 처음부터
	 * {@link TrialWarning.Stage#MARK} 구간(≤50) 안에서 시작하기 때문이다. 그 판단은 이 파일이
	 * 아니라 {@link TrialRisks#stageJustChanged(int, int)} 가 한다 — 예고 길이
	 * ({@code warnTicks})를 넘겨주기만 하면 된다.
	 */
	private static void warn(ServerLevel end, List<ServerPlayer> members, Downpour run, long now,
			TrialCatalog.Risk.EndRain risk) {
		Volley volley = run.volley();
		if (volley == null) {
			// 볼리 사이의 빈 시간이거나, 방금 터져 land 가 비운 틱이다.
			return;
		}
		int remaining = (int) Math.min(Integer.MAX_VALUE, volley.landsAt() - now);
		if (remaining <= 0) {
			return;
		}
		// 나눠 그린다. 위상을 now 에서 뽑으므로 매 틱 한 칸씩 옮겨 가며 빈자리가 메워진다 —
		// level.getGameTime() 을 부르면 얼어붙은 판에서 같은 몫만 되풀이돼 고리가 안 닫힌다.
		int stride = markStride(risk);
		int phase = markPhase(now, stride);
		// 먼지는 고리 밖에서 한 번만 만든다. 지점 수만큼 새로 만들 이유가 없다.
		ParticleOptions mark = TrialWarning.dust(MARK_COLOR);
		for (Vec3 spot : volley.spots()) {
			markRing(end, spot, risk.radius(), mark, stride, phase);
			dropOrb(end, spot, remaining, risk);
		}
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage == null) {
			return;
		}
		if (!TrialRisks.stageJustChanged(remaining, risk.warnTicks())) {
			return;
		}
		// ⚠ 사람마다 TrialWarning.soundFor 다. 예전에는 TrialWarning.sound 를 사람 자리마다
		// 불렀는데, 그쪽은 자리에 소리를 놓는 것이라 반경 안의 전원에게 나간다 — 볼리마다 넷이
		// 모여 있으면 각자 네 겹으로 들었다.
		for (ServerPlayer member : members) {
			TrialWarning.soundFor(end, member, stage);
		}
	}

	/**
	 * 고리 한 바퀴 중 이번 틱 몫을 찍는다.
	 *
	 * <p>{@link TrialWarning#markGround} 를 부르지 않고 직접 그리는 이유는 <b>점 수 하나</b>다.
	 * 그쪽은 반경이 작아도 40점을 찍는데({@code BASE_POINTS}) 이 카드는 작은 고리를 수십 개
	 * 띄우므로 그 하한이 그대로 예산을 넘긴다. 하한을 낮추면 바닥 고리를 쓰는 카드 <b>전부</b>의
	 * 모습이 바뀌므로, 남의 연출을 건드리지 않고 이 카드만 제 밀도를 쓰는 길이 이것뿐이다
	 * ({@link #MARK_POINT_GAP}). {@code TrialEnderPulse} 가 같은 이유로 제 고리를 직접 그린다.
	 *
	 * <p><b>첫 {@code boolean} 을 {@code false} 로 되돌리지 말 것.</b> 짧은 형태는 서버에서
	 * 32칸으로 잘리고 클라이언트가 한 번 더 거른다({@link TrialWarning} 의 「거리 제한을 끄고
	 * 보낸다」). 아레나 반경이 40 이라 흩어진 팀원에게는 고리의 절반이 없는 것이 된다.
	 *
	 * <p>둘째 {@code boolean}({@code alwaysShow})은 첫 깃발이 켜져 있으면 무의미하고, 사용자의
	 * 「파티클 줄이기」 설정을 우리가 뒤집을 이유도 없어 {@code false} 로 둔다.
	 *
	 * <p>높이는 <b>중심 하나</b>를 쓴다. 점마다 지표를 묻는 길도 있지만({@code
	 * TrialEnderPulse.Ground}), 그러면 허공에 걸린 점을 건너뛰어 <b>고리가 끊긴다</b> — 피해
	 * 판정({@link TrialRisks#insideMark})은 높이를 보지 않으므로 끊긴 자리가 안전해 보이는데
	 * 실제로는 맞는다. 반경 2.5 안에서 지표가 갈리는 일은 드물고, 갈려도 고리가 조금 파묻히는
	 * 쪽이 거짓말을 하는 쪽보다 낫다.
	 */
	private static void markRing(ServerLevel end, Vec3 center, double radius, ParticleOptions type,
			int stride, int phase) {
		int points = ringPoints(radius);
		int step = Math.max(1, stride);
		// floorMod 라야 음수 phase 에서도 0..step-1 로 떨어진다. 되감긴 판의 now 가 음수일 수 있다.
		for (int index = Math.floorMod(phase, step); index < points; index += step) {
			double angle = (Math.PI * 2.0 * index) / points;
			end.sendParticles(type, true, false,
					center.x + Math.cos(angle) * radius,
					center.y + GROUND_OFFSET,
					center.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 하늘에서 떨어지는 구체를 이번 틱 자리에 찍는다.
	 *
	 * <p>높이가 <b>남은 틱에 정비례</b>하므로({@link #orbHeight}) 구체가 지표에 닿는 틱과
	 * 착탄 틱이 어긋날 수 없다. 가속을 넣지 않은 것은 일부러다 — 실제 중력으로 떨어뜨리면 예고
	 * 대부분을 하늘 높이에서 보내다가 마지막 몇 틱에 쏟아지듯 내려와, <b>구체를 보고 남은
	 * 시간을 읽을 수 없다.</b> 선형이면 「절반 내려왔으니 절반 남았다」가 그대로 읽힌다.
	 * {@code TrialFireball.flightProgress} 와 {@code DragonFireBarrage} 도 같은 이유로 선형이다.
	 *
	 * <p>바닥은 {@code spot.y} 다. {@link TrialRisks#reserveSpots} 가 자리를 내줄 때 이미 그 칸의
	 * 지표를 물어 담아 주므로, <b>자리마다 바닥 높이가 달라도 구체는 제 자리의 땅에 닿는다.</b>
	 * 여기서 하이트맵을 다시 두드리지 않는 이유이기도 하다 — 매 틱 지점 수만큼 묻게 된다.
	 *
	 * <p>{@code GROUND_OFFSET} 을 더해 고리와 같은 높이에서 끝난다. 마지막으로 그려지는 틱은
	 * {@code remaining == 1} 이므로 구체는 지표 위 0.8칸쯤에서 마지막으로 보이고, 다음 틱에
	 * 터진다.
	 *
	 * <p><b>긴 형태</b>다. 짧은 형태로 되돌리면 32칸 밖 구체가 통째로 사라져, 아레나 반대편에서
	 * 보면 하늘은 비어 있는데 바닥만 터진다.
	 */
	private static void dropOrb(ServerLevel end, Vec3 spot, int remaining,
			TrialCatalog.Risk.EndRain risk) {
		double height = orbHeight(remaining, risk.warnTicks());
		end.sendParticles(ParticleTypes.WITCH, true, false,
				spot.x, spot.y + GROUND_OFFSET + height, spot.z,
				ORB_POINTS_PER_SPOT, ORB_SPREAD, ORB_SPREAD, ORB_SPREAD, 0.0);
	}

	// ------------------------------------------------------------------ 착탄

	/**
	 * 고리 하나가 터진다. 블록은 건드리지 않고 불도 붙이지 않는다.
	 *
	 * <p><b>팀에게 한 번만</b> 들어간다. 안에 선 사람을 모두 때리면 넷이 모여 있을 때 네 배가 되어
	 * 팀 체력 20 을 훨씬 넘긴다 — 함께 움직이는 것이 공유 체력 게임의 올바른 대응인데 그것이
	 * 전멸이 된다. 「연쇄 포격」이 같은 이유로 같은 모양을 쓴다.
	 *
	 * <p>고리끼리는 {@link TrialRisks#reserveSpots} 가 떼어 놓으므로 <b>한 사람이 한 볼리에 한
	 * 발</b>만 맞는다. {@code TrialRisks.worstCaseTickDamage} 가 이 카드를 「피해 × 1」로 세는
	 * 근거가 그 규칙이고, 규칙을 지우면 그 숫자가 거짓이 된다.
	 *
	 * <p>팀원 목록을 직접 돈다. 상자로 후보를 추릴 이유가 없다 — 어차피 한 명만 세고, 팀이 아닌
	 * 사람(관전자·다른 판의 누구)을 때릴 일도 없어야 한다.
	 *
	 * <p>피해원에 실체를 달지 않는다. 엔드 섬 가장자리에서 밀리면 대응 불가 즉사이고 공유 체력이라
	 * 한 사람의 낙사가 팀 전체를 끝낸다. 폭발 피해형을 쓰므로 폭발 보호는 그대로 듣는다 —
	 * 대비한 사람이 손해 보지 않아야 한다.
	 *
	 * <h2>연출이 보라 한 겹뿐이다</h2>
	 *
	 * <p>{@code EXPLOSION}(회백색 덩어리)을 걷어냈다. 사람이 「이펙트도 보라색으로」라고 했는데
	 * 그 입자가 정확히 「TNT 처럼 보인다」의 원인이고, 위에 보라를 한 겹 얹어 가리던 것이
	 * 이전 구현이다. 가리지 말고 <b>보라만 남기는</b> 쪽으로 갔다.
	 *
	 * <p>덮어씌우던 {@code REVERSE_PORTAL} 도 함께 걷어냈다 — 수명이 <b>60~61틱</b>이라 터진 뒤
	 * 3초 동안 자국이 남았고, 볼리 간격이 40~60틱이라 <b>그 자국이 다음 볼리의 고리와 겹쳤다.</b>
	 * 이미 안전한 자리가 위험해 보이는 것이 이 전투가 가장 피하는 종류의 거짓말이다.
	 */
	private static void detonate(ServerLevel end, List<ServerPlayer> members, Vec3 at,
			TrialCatalog.Risk.EndRain risk) {
		// 착탄 연출도 긴 형태로 보낸다. 맞는 사람은 어차피 가깝지만 나머지 셋이 「저기 떨어졌다,
		// 피했구나」를 봐야 예고가 완결된다. 아레나 반경 40 이면 흩어진 팀원은 쉽게 32칸을 넘는다.
		end.sendParticles(ParticleTypes.WITCH, true, false, at.x, at.y + 0.3, at.z,
				impactPoints(risk), risk.radius() * 0.45, 0.25, risk.radius() * 0.45, 0.02);

		for (ServerPlayer member : members) {
			if (!TrialRisks.insideMark(member.position(), at, risk.radius())) {
				continue;
			}
			member.hurtServer(end, end.damageSources().explosion(null, null), risk.damage());
			// 고리 하나는 한 발이다. 나머지는 같은 발의 두 번째 몫이라 세지 않는다.
			return;
		}
	}

	/**
	 * 착탄음. <b>지점마다가 아니라 사람마다</b> 한 번씩 낸다.
	 *
	 * <p>이유가 둘이다.
	 *
	 * <ul>
	 *   <li><b>닿지 않는다.</b> 바닐라 소리 사거리는 볼륨 1 이하면 16칸이다. 지점에서 울리면
	 *       아레나 반대편(최대 80칸)에 선 사람은 팀이 폭격당하는 소리를 아예 못 듣는다.
	 *       {@link #warn} 의 경고음이 이미 사람마다 그 자리에서 울리므로 답이 같다</li>
	 *   <li><b>한 볼리가 한 틱에 통째로 터진다.</b> 지점마다 울리면 같은 소리가 지점 수만큼
	 *       겹쳐 한 틱에 쏟아진다 — 45곳일 때 이미 45장이었고 90곳이면 90장이다. 사람마다면
	 *       팀이 넷이라 <b>언제나 넉 장</b>이고, 각자 정확히 한 번 듣는다</li>
	 * </ul>
	 *
	 * <p>⚠ <b>그 「각자 한 번」이 한동안 거짓이었다.</b> 사람마다 {@code end.playSound} 를 부르고
	 * 있었는데 그것은 자리에 소리를 놓는 것이라 반경 안의 <b>전원</b>에게 나간다 — 넷이 16칸 안에
	 * 모여 있으면 넉 장이 저마다 넷에게 가서 <b>각자 네 번</b> 들렸다. 지금은
	 * {@link TrialWarning#playEach} 가 사람마다 그 사람의 연결로 직접 보낸다.
	 *
	 * <p>소리는 {@link SoundEvents#ENDER_EYE_DEATH} 다. 왜 이것인지는 클래스 설명의 「소리」에
	 * 적어 두었다 — 전에 쓰던 {@code DRAGON_FIREBALL_EXPLODE} 는 이름만 드래곤이고 파일은
	 * {@code entity.generic.explode} 와 같았다.
	 *
	 * <p>음높이를 0.7 로 내린다. 이 소리는 원래 엔더의 눈 하나가 깨지는 작은 소리이고, 여기서는
	 * 수십 곳이 한꺼번에 터지는 순간이라 낮을수록 무게가 맞는다.
	 */
	private static void impactSound(ServerLevel end, List<ServerPlayer> members) {
		TrialWarning.playEach(end, members, SoundEvents.ENDER_EYE_DEATH, 1.0F, 0.7F);
	}

	private static void finish(String key) {
		if (RAINS.remove(key) == null) {
			// 이미 돌려줬다. 두 번 놓는 것 자체는 무해하지만, 여기서 걸러 두면 「끝나는 틱에
			// 정확히 한 번」이 코드에 적힌 사실이 된다.
			return;
		}
		TrialRisks.releaseSpots(key);
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 값이 굴릴 수 있는 모양인가.
	 *
	 * <p>{@code TrialRisksTest} 가 카드 값에서 이미 확인하지만, 여기서도 막는 것은 <b>시험이 보는
	 * 것이 카드 목록이지 이 함수의 인자가 아니기</b> 때문이다. 범위가 뒤집힌 값이 들어오면
	 * {@code nextIntBetweenInclusive} 가 터지고, 그 자리는 전투 한가운데다.
	 */
	static boolean usable(TrialCatalog.Risk.EndRain risk) {
		return risk.durationTicks() > 0 && risk.warnTicks() >= 0
				&& risk.minInterval() > 0 && risk.maxInterval() >= risk.minInterval()
				&& risk.minSpots() > 0 && risk.maxSpots() >= risk.minSpots()
				&& risk.radius() > 0.0;
	}

	/**
	 * 아직 비가 내리는 시간인가.
	 *
	 * <p>받은 바로 그 틱({@code elapsed == 0})은 아니다 — 그 틱에는 아직 아무 볼리도 시작되지
	 * 않았다. 마지막 틱({@code elapsed == durationTicks})은 포함한다.
	 */
	static boolean raining(long elapsed, TrialCatalog.Risk.EndRain risk) {
		return elapsed > 0L && elapsed <= risk.durationTicks();
	}

	/**
	 * 지금 여는 볼리의 착탄이 30초 안에 들어오는가.
	 *
	 * <p>이 검사가 「예고는 반드시 착탄으로 끝난다」를 지키는 자리다. 끝나기 직전에 열면 표식만
	 * 뜨고 사라지는 볼리가 생기고, 그러면 사람은 <b>피할 필요가 없었던 자리에서 비킨다</b> —
	 * 한 번이라도 그러면 다음부터 표식을 믿지 않는다.
	 */
	static boolean landsInWindow(long elapsed, TrialCatalog.Risk.EndRain risk) {
		return elapsed + risk.warnTicks() <= risk.durationTicks();
	}

	/** 다음 볼리까지의 간격. 볼리마다 새로 굴린다 — 고정하면 박자를 외워 버린다. */
	static int rolledInterval(RandomSource random, TrialCatalog.Risk.EndRain risk) {
		return random.nextIntBetweenInclusive(risk.minInterval(), risk.maxInterval());
	}

	/** 이번 볼리에 <b>잡아 보려는</b> 지점 수. 실제로 몇 자리가 나오는지는 예약이 정한다. */
	static int rolledSpots(RandomSource random, TrialCatalog.Risk.EndRain risk) {
		return random.nextIntBetweenInclusive(risk.minSpots(), risk.maxSpots());
	}

	/**
	 * 구체가 지금 지표에서 얼마나 떠 있는가(블록).
	 *
	 * <p><b>남은 틱에 정비례한다.</b> 그래서 {@code remaining == 0} 인 틱, 곧 착탄 틱에 정확히
	 * 0 이고 볼리가 열리는 틱({@code remaining == warnTicks})에 정확히 {@link #ORB_DROP_HEIGHT}
	 * 다 — 구체가 먼저 닿거나 늦게 닿는 길이 값에 없다.
	 *
	 * <p>{@code remaining} 을 {@code [0, warnTicks]} 로 자른다. 예고 길이를 넘는 값이 들어오면
	 * 구체가 시작 높이보다 위에서 출발한 것으로 그려지고, 음수면 땅 밑에서 올라온다.
	 */
	static double orbHeight(int remaining, int warnTicks) {
		if (warnTicks <= 0) {
			return 0.0;
		}
		int clamped = Math.max(0, Math.min(warnTicks, remaining));
		return ORB_DROP_HEIGHT * clamped / warnTicks;
	}

	/**
	 * 고리 한 바퀴에 찍는 점 수.
	 *
	 * <p>둘레를 {@link #MARK_POINT_GAP} 으로 나누고 {@link #MARK_MIN_POINTS} 를 하한으로 둔다.
	 * {@link TrialWarning#ringPoints} 를 쓰지 않는 까닭은 {@link #MARK_POINT_GAP} 에 적어 두었다 —
	 * 그쪽 하한 40 은 이 반경에서 간격 0.39칸이라 지점이 수십 곳이면 예산이 무너진다.
	 */
	static int ringPoints(double radius) {
		if (!(radius > 0.0)) {
			return 0;
		}
		int wanted = (int) Math.ceil((Math.PI * 2.0 * radius) / MARK_POINT_GAP);
		return Math.max(MARK_MIN_POINTS, wanted);
	}

	/** 그 반경에서 실제로 벌어지는 점 사이 거리(칸). 「고리로 읽히는가」를 숫자로 묻는 값이다. */
	static double ringGap(double radius) {
		int points = ringPoints(radius);
		if (points <= 0) {
			return 0.0;
		}
		return (Math.PI * 2.0 * radius) / points;
	}

	/**
	 * 고리 한 바퀴 중 <b>한 틱에</b> 찍히는 점 수의 상한.
	 *
	 * <p>위상에 따라 실제로는 하나 적을 수 있다({@code 18점을 6으로 나누면 3·3·3·3·3·3}).
	 * 예산을 묻는 자리는 늘 나쁜 쪽을 봐야 하므로 올림으로 돌려준다.
	 */
	static int strokePoints(double radius, int stride) {
		int points = ringPoints(radius);
		if (points <= 0) {
			return 0;
		}
		int step = Math.max(1, stride);
		return (points + step - 1) / step;
	}

	/**
	 * 떨어지는 구체가 <b>한 틱에</b> 쓰는 점 수. 지점 수 × {@link #ORB_POINTS_PER_SPOT} 다.
	 *
	 * <p>구체는 나눠 그릴 수 없으므로({@link #warn} 의 「구체는 나눠 그리지 않는다」) 이 몫이
	 * <b>고정 비용</b>이고, 고리는 남은 것으로 그린다.
	 */
	static int orbPoints(TrialCatalog.Risk.EndRain risk) {
		return Math.max(0, risk.maxSpots()) * Math.max(0, ORB_POINTS_PER_SPOT);
	}

	/**
	 * 고리 한 바퀴를 몇 틱에 나눠 그릴지.
	 *
	 * <p>「한 바퀴 전부 ÷ <b>구체를 뺀</b> 예산」을 올림한 값이고 {@link #MARK_MAX_STRIDE} 에서
	 * 멈춘다. 카드 값에서 뽑으므로 지점 수를 다시 손대는 사람이 <b>패킷도 함께 따라오게</b> 된다.
	 *
	 * <p>⚠ <b>상한에 걸리면 예산을 넘을 수 있다.</b> 파티클 수명이 정한 8틱은 협상할 수 없는
	 * 쪽이라 그 앞에서 멈추는 것이 맞다 — 넘치는지는 {@link #tickPoints} 가 숫자로 드러내고
	 * {@code TrialEndRainTest} 가 본다.
	 *
	 * <p>지금 값으로 세어 보면 이렇다(반경 2.5 → 고리 18점).
	 *
	 * <ul>
	 *   <li>45곳 — 구체 45점, 남은 예산 355, {@code ⌈45×18/355⌉ = 3} 틱. 고리 270 + 구체 45 =
	 *       <b>315점/틱</b></li>
	 *   <li>90곳 — 구체 90점, 남은 예산 310, {@code ⌈90×18/310⌉ = 6} 틱(상한과 같다).
	 *       고리 270 + 구체 90 = <b>360점/틱</b></li>
	 * </ul>
	 *
	 * <p>⚠ <b>90곳이 상한에 꼭 맞는다.</b> 여기서 지점을 더 늘리거나 반경을 키우면 상한에 걸려
	 * 예산을 넘고, 그때는 {@link #MARK_POINT_GAP} 을 벌리는 것 말고 방법이 없다.
	 */
	static int markStride(TrialCatalog.Risk.EndRain risk) {
		int perRing = ringPoints(risk.radius());
		int spots = Math.max(0, risk.maxSpots());
		int budget = MARK_BUDGET - orbPoints(risk);
		if (perRing <= 0 || spots <= 0 || budget <= 0) {
			return 1;
		}
		int whole = spots * perRing;
		int stride = (whole + budget - 1) / budget;
		return Math.max(1, Math.min(MARK_MAX_STRIDE, stride));
	}

	/**
	 * <b>한 틱에</b> 바닥 고리로 나가는 점 수의 최대.
	 *
	 * <p><b>한 바퀴 전부가 아니라 이번 틱에 찍는 몫</b>이다. 나눠 그리지 않으면 이 값이
	 * {@code 지점 수 × 한 바퀴}가 되고, 그것이 고리를 나눠 그리게 된 까닭이다.
	 */
	static int markPoints(TrialCatalog.Risk.EndRain risk) {
		return Math.max(0, risk.maxSpots()) * strokePoints(risk.radius(), markStride(risk));
	}

	/**
	 * <b>예고 한 틱에</b> 이 카드가 쓰는 점 수 전부. 고리 + 구체다.
	 *
	 * <p>「상한 안인가」를 숫자로 물을 수 있는 값이라 값에서 직접 뽑는다 — 지점 수나 반경을 고치는
	 * 사람은 피해만 보고 패킷은 보지 않는다.
	 *
	 * <p><b>볼리는 언제나 하나뿐</b>이라 이 값이 그대로 최대다. {@code minInterval}(40)이
	 * {@code warnTicks}(30)보다 커서 앞 볼리가 터진 뒤에야 다음 볼리가 열리고,
	 * {@code TrialRisksTest} 가 그 부등식을 카드 값에서 지킨다.
	 */
	static int tickPoints(TrialCatalog.Risk.EndRain risk) {
		return markPoints(risk) + orbPoints(risk);
	}

	/**
	 * 착탄 <b>한 자리</b>에 쓰는 점 수.
	 *
	 * <p>{@link #IMPACT_BUDGET} 을 지점 수로 나눈다 — 지점이 늘면 한 자리의 몫이 저절로 줄어
	 * <b>한 틱 합계가 예산을 넘지 않는다.</b> 90곳이면 8점(합 720), 45곳이면 상한에 걸려 12점
	 * (합 540)이다. 고치기 전에는 자리마다 25점을 고정으로 써서 45곳에 이미 1125점이었다.
	 */
	static int impactPoints(TrialCatalog.Risk.EndRain risk) {
		int spots = Math.max(1, risk.maxSpots());
		return Math.max(1, Math.min(IMPACT_MAX_POINTS, IMPACT_BUDGET / spots));
	}

	/** 착탄하는 그 틱에 나가는 점 수 전부. {@link #IMPACT_BUDGET} 과 견주는 값이다. */
	static int impactTickPoints(TrialCatalog.Risk.EndRain risk) {
		return Math.max(0, risk.maxSpots()) * impactPoints(risk);
	}

	/**
	 * 이번 틱에 그릴 고리 몫.
	 *
	 * <p>{@code now} 에서 뽑는다 — {@code level.getGameTime()} 을 부르면 {@code TrialFreeze} 가
	 * 판을 멈춘 동안 같은 몫만 되풀이돼 고리가 영영 안 닫힌다.
	 *
	 * <p>{@code floorMod} 인 이유. 복원 직후에는 {@code now} 가 음수일 수 있고, 그냥 {@code %}
	 * 로 나누면 음수 위상이 나와 {@link #markRing} 의 시작 번호가 범위를 벗어난다.
	 */
	static int markPhase(long now, int stride) {
		return (int) Math.floorMod(now, Math.max(1L, stride));
	}
}

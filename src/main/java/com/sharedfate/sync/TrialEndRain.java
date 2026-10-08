package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
 *   <li>바닥이 <b>보라 투명 면</b>으로 덮인다({@link TrialEndRainPanel}) — 「여기가 위험하다」</li>
 *   <li><b>동시에</b> 하늘에서 <b>빨간 구체</b>가 그 자리로 떨어지기 시작한다 — 「곧 온다」</li>
 *   <li>구체가 <b>땅에 닿는 그 틱에</b> 터진다</li>
 * </ol>
 *
 * <p>착탄 시각은 예전과 같다. 구체는 예고를 <b>다시 말하는</b> 것이지 예고를 바꾸는 것이
 * 아니다 — 높이가 남은 틱에 정비례하므로({@link #orbHeight}) 구체가 바닥에 닿는 틱과
 * {@code landsAt} 은 같을 수밖에 없다. 먼저 닿거나 늦게 닿으면 예고가 거짓말이 된다.
 *
 * <p>곧 <b>바닥 면이 「어디」를, 구체 높이가 「언제」를 맡는다.</b> 사람이 이번에 <b>그 둘을 함께</b>
 * 올리라고 했고(「보라색 바닥으로 … 떨어지는걸 좀더 보이게」) 역할이 갈려 있으므로, 어느 한쪽을
 * 손대도 다른 쪽이 가리는 것을 뺏지 않는다. ⚠ <b>구체를 가속으로 바꾸면 「언제」가 사라진다</b> —
 * 등속인 것은 사람이 정한 설계다({@link #dropOrb}).
 *
 * <h2>바닥 표식이 <b>고리에서 면으로</b> 바뀌었다</h2>
 *
 * <p>사람이 플레이해 보고 <b>「보라색 테두리로는 가시성 진짜 안좋으니까 차라리 보라색 바닥으로
 * 투명바닥으로 표시하고 떨어지는걸 좀더 보이게 가시성을 올려봐」</b>라고 했다. 한 볼리에 90개가
 * 뜨는 카드라 작은 고리들이 서로 묻혀 테두리로는 읽히지 않았다.
 *
 * <p>그래서 바닥 표식을 <b>디스플레이 개체로 깐 보라 반투명 면</b>으로 갈았다. 수법은
 * {@link TrialEndRainPanel} 에 있고, 그 파일이 <b>「최후의 저항」의 부채꼴 면</b>
 * ({@link DragonLastStandConePanel})을 그대로 따른다 — 이 저장소가 같은 물음을 이미 한 번 풀었다.
 *
 * <p>⚠ <b>이 파일은 이제 바닥에 점을 한 개도 안 찍는다.</b> 얻은 것이 둘이다.
 *
 * <ul>
 *   <li><b>경계가 더 또렷해졌다.</b> 고리는 둘레 15.71칸에 점 18개라 점 사이가 <b>0.87칸</b>이었고,
 *       면은 원에 내접하는 계단이라 경계가 <b>0.19칸</b> 안쪽에서 끝난다
 *       ({@link TrialEndRainPanel#edgeGap})</li>
 *   <li><b>점 예산 270점이 비었다.</b> 그 몫이 전부 구체로 갔다 — 사람이 같은 문장에서 「떨어지는걸
 *       좀더 보이게」라고 한 자리이고, 고리를 그대로 두었다면 <b>구체에 줄 점이 한 점도
 *       없었다</b>({@link #POINT_BUDGET})</li>
 * </ul>
 *
 * <p>⚠ <b>아래 설명에 「고리」라고 적힌 자리는 이제 면이다.</b> 겹침 이야기(깊이·넓이·전멸)는
 * <b>반경 2.5 짜리 원이 겹치는</b> 이야기라, 표식이 선에서 면으로 바뀌어도 한 줄도 안 바뀐다.
 * 오히려 겹친 자리가 <b>반투명 두 겹으로 짙어져 눈에 보인다</b> — 가장 위험한 자리를 가장 짙게
 * 칠하는 셈이다.
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
 * <h2>색이 둘이다 — <b>바닥은 보라, 하늘은 빨강</b></h2>
 *
 * <p>경위가 넷이다. <b>네 번 다 사람이 플레이해 보고 정했다.</b>
 *
 * <ol>
 *   <li>처음에는 {@link TrialWarning#markGround} 를 그냥 불러 <b>규약의 빨강</b>
 *       ({@link TrialWarning.Colors#DEADLY})을 썼다</li>
 *   <li>「이펙트도 보라색으로 바꿔줘」라고 해서 보라로 갔는데, 규약의 보라
 *       ({@code MARKED} = 「너 하나를 노린다」)는 「표적」({@link TrialDragonFocus})이 쓰고 있어
 *       그것과 갈라 놓으려고 <b>자홍</b>({@code 0xC800C8})을 따로 만들어 썼다</li>
 *   <li><b>「보라색이아닌 빨간색원으로다시 복귀하자 이번건 너무 가시성이안좋아」</b>라고 해서
 *       규약의 빨강으로 되돌렸다</li>
 *   <li><b>「보라색 테두리로는 가시성 진짜 안좋으니까 차라리 보라색 바닥으로 투명바닥으로
 *       표시하고」</b> — 바닥이 <b>보라 면</b>이 됐다. ⚠ 사람은 이때 테두리가 <b>보라</b>인 줄
 *       알고 말했는데 코드는 ③에서 이미 <b>빨강</b>이었다. 고친 것은 색이 아니라 <b>선 → 면</b>
 *       이고, 바닥 색만 사람 말대로 보라로 갔다</li>
 * </ol>
 *
 * <p>그래서 지금 <b>한 카드에 색이 둘</b>이다. 섞인 것이 아니라 <b>배경이 둘이라 색도 둘</b>이다.
 *
 * <ul>
 *   <li><b>바닥 면 — 보라</b>({@link TrialEndRainPanel#GLASS_COLOR}). 엔드 바닥은 흰 엔드스톤이라
 *       보라가 가장 멀리서 갈린다</li>
 *   <li><b>구체와 착탄 — 빨강</b>({@link #MARK_COLOR}). 구체는 <b>하늘을 배경으로</b> 본다. 엔드
 *       하늘은 어두운 보랏빛이라 보라 구체는 배경에 묻힌다 — ③에서 사람이 「가시성이안좋아」라고
 *       한 것이 정확히 그것이었다고 본다</li>
 * </ul>
 *
 * <p>⚠ <b>둘을 같은 색으로 묶지 말 것.</b> 묶으면 바닥이든 하늘이든 한쪽은 반드시 배경에 묻힌다.
 * 「저 구체가 이 자리로 온다」가 색으로 안 이어지는 대신 <b>자리</b>로 이어진다 — 구체는 제
 * 면의 가운데 바로 위에서 수직으로 내려오고, 그것이 90곳이 동시에 떠 있어도 헷갈리지 않는 까닭이다.
 *
 * <p>⚠ <b>{@link TrialWarning.Colors} 에 색을 더하지 않았다.</b> 구체는 규약의 빨강 하나를 그대로
 * 쓰고, 면의 보라는 우리가 고른 16진수가 아니라 <b>색유리 블록이 들고 있는 것</b>이다
 * ({@link TrialEndRainPanel#glass()} — 부채꼴 면이 붉은 색유리로 같은 판단을 했다). 다섯째를
 * 더하면 그 순간 규약이 규약이 아니게 된다({@code Colors} 의 설명).
 *
 * <p>⚠ <b>이제 「자리 폭격」·「기둥 화염구」·「연쇄 포격」의 빨강과 섞이지 않는다.</b> 그것들은
 * <b>빨간 고리</b>이고 이 카드는 <b>보라 면</b>이라, 한 판에 여러 카드가 떠도 화면에서 갈린다 —
 * 이 카드가 빨강으로 돌아가 있던 동안에는 「수(90곳) · 구체 · 크기」 셋으로만 갈렸고 그것이
 * 「낙뢰」를 노랑으로 떼어 놓은 것과 같은 종류의 빚이었다. 면이 그 빚을 갚았다.
 *
 * <h2>구체는 빨강이라 입자를 갈아야 했다</h2>
 *
 * <p>⚠ <b>구체가 쓰던 {@code WITCH} 로는 빨강을 만들 수 없다.</b> 26.3 의
 * {@code SpellParticle.WitchProvider} 가 {@code setColor(f, 0, f)}({@code f ∈ [0.35, 0.85)})로
 * <b>제 색을 자홍으로 직접 칠한다</b> — 서버가 무엇을 보내든 자홍이다. 자홍이던 때는 그것이
 * 장점이었고 지금은 막힌 길이다.
 *
 * <p>그래서 <b>{@code ENTITY_EFFECT}</b> 로 갈았다. 고른 이유가 셋이다.
 *
 * <ul>
 *   <li><b>색을 우리가 정한다.</b> 26.3 의 {@code ParticleResources} 가 이 타입에
 *       {@code SpellParticle.MobEffectProvider} 를 물리는데, 그쪽은
 *       {@code ColorParticleOption} 이 실어 보낸 ARGB 를 그대로
 *       {@code setColor}·{@code setAlpha} 한다</li>
 *   <li><b>보이는 것은 그대로다.</b> 만들어지는 입자가 {@code WITCH} 와 <b>같은
 *       {@code SpellParticle} 클래스</b>라 재질(애니메이션되는 반투명 별)도, 중력 {@code -0.1}
 *       로 천천히 떠올라 떨어지는 구체 뒤로 꼬리가 들리는 것도 같다. 색만 바뀐다</li>
 *   <li><b>수명이 그대로다.</b> 같은 클래스라 {@code (int)(8.0 / (굴림 × 0.8 + 0.2))} 곧
 *       <b>8~40틱</b>이고, 아래 「입자 수명」의 계산이 한 줄도 안 바뀐다</li>
 * </ul>
 *
 * <p>⚠ <b>알파를 빼먹으면 통째로 안 보인다.</b> {@code ColorParticleOption.create(타입, int)} 가
 * 받는 것은 RGB 가 아니라 <b>ARGB</b> 이고, 최상위 바이트가 0 이면 완전 투명이다. 그래서
 * {@link #ORB_ARGB} 가 {@link #MARK_COLOR} 에 {@code 0xFF000000} 을 얹는다.
 *
 * <h2>구체를 네 배로 키웠다 — <b>알맹이와 겉을 따로 보낸다</b></h2>
 *
 * <p>사람이 「떨어지는걸 좀더 보이게 가시성을 올려봐」라고 했다. 전에는 지점마다 <b>한 점</b>이
 * 전부였고, 그것이 유일한 값이었던 까닭은 <b>고리가 예산의 270점을 쓰고 있었기</b> 때문이다
 * ({@link #POINT_BUDGET}). 고리를 면으로 갈면서 그 270점이 비었으므로 <b>지점마다 네 점</b>이
 * 됐다({@link #ORB_POINTS_PER_SPOT}).
 *
 * <p>⚠ <b>입자 하나의 크기는 우리가 못 키운다.</b> 먼지의 {@code scale} 을 올리면 커지기는
 * 하지만 26.3 {@code DustParticleBase} 가 <b>수명에도 그 {@code scale} 을 곱한다</b>
 * ({@code lifetime = max(1, (int)(8.0/(굴림×0.8+0.2)) × scale)}) — 2.0 이면 수명이 16~80틱이라
 * 볼리 간격(40~60틱)을 넘어 <b>지난 볼리의 자국이 다음 볼리에 남는다.</b> 그래서 키운 것은
 * <b>개수와 꼴</b>이고 크기는 그대로다.
 *
 * <p>네 점을 두 벌로 나눠 보낸다. 역할이 다르다.
 *
 * <ul>
 *   <li><b>알맹이 — 먼지 두 점</b>({@link #coreParticle}). 중력이 없어 <b>찍힌 자리에 그대로
 *       선다</b>. 구체가 내려가면서 지나온 길이 하늘에서 바닥까지 <b>점선 기둥</b>으로 남아,
 *       고개를 숙이지 않아도 「저 기둥이 꽂히는 자리」가 보인다 — 바닥 면을 못 보는 각도에서
 *       이것이 「어디」를 대신 말한다</li>
 *   <li><b>겉 — 별 두 점</b>({@link #orbParticle}). {@code SpellParticle} 이라 중력 {@code -0.1}
 *       로 천천히 떠올라 <b>꼬리가 들린다</b>. {@code y} 쪽으로만 넓게 흩어
 *       ({@link #ORB_HALO_RISE}) 떨어지는 줄기로 보이게 한다</li>
 * </ul>
 *
 * <p>⚠ <b>화면에 사는 입자 수는 늘지 않았다.</b> 수명이 평균 16틱이라 한 지점에 살아 있는 점이
 * {@code 4 × 16 = 64}개쯤인데, 고리 시절에도 {@code (3 + 1) × 16} 에 지점 수를 곱한 만큼이
 * 떠 있었다 — <b>같은 예산을 고리에서 구체로 옮긴 것</b>이지 더 쓴 것이 아니다.
 *
 * <p>「표적」({@link TrialDragonFocus})과 섞이지 않는다. 그쪽도 먼지를 쓰지만 <b>사람을 따라다니는
 * 작은 고리</b>이고 이쪽은 <b>하늘에서 내려오는 기둥</b>이라, 재질이 같아져도 움직임과 자리가
 * 전혀 다르다.
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
 * 볼리 간격이 40~60틱이라 <b>지난 볼리의 자국이 다음 볼리의 표식과 겹친다.</b>
 *
 * <p>그래서 쓰는 것이 둘 다 <b>수명 8~40틱</b>짜리다. 우연이 아니라 26.3 의 두 클래스가
 * 같은 식을 쓴다 — {@code (int)(8.0 / (굴림 × 0.8 + 0.2))}.
 *
 * <ul>
 *   <li>구체의 알맹이 — {@code DustParticleBase}({@link #coreParticle}). 중력이 없어 <b>제자리에
 *       그대로 선다</b>. 그것이 기둥이 되는 까닭이다. {@code scale} 을 1.0 에서 올리면 <b>수명도
 *       함께 곱해지므로</b> 올리지 말 것(위의 「구체를 네 배로 키웠다」)</li>
 *   <li>구체의 겉 — {@code SpellParticle}({@code ENTITY_EFFECT}). 중력이 {@code -0.1} 이라 아주
 *       천천히 떠올라, 떨어지는 구체 뒤로 꼬리가 들린다. <b>전에는 같은 클래스를 쓰는
 *       {@code WITCH} 였고 수명도 같았다</b> — 색을 우리가 정할 수 없어 갈았을 뿐이라 이
 *       계산은 한 줄도 안 바뀐다</li>
 * </ul>
 *
 * <p><b>바닥 표식에는 이 계산이 더 걸리지 않는다.</b> 면은 개체라 수명이 파티클이 아니라
 * <b>심지</b>로 정해지고({@link TrialEndRainPanel#fuseTicks}), 정상 경로에서는 착탄하는 그 틱에
 * 지워진다. 곧 <b>「터진 자리는 즉시 안전」이 고리 시절보다 더 정확해졌다</b> — 고리는 마지막에
 * 찍은 점이 최대 40틱을 더 살아 터진 자리에 2초쯤 남아 있었다.
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
 * <p>그래서 한 번은 실제로 엔더 소리 파일을 쓰는 것으로 바꿨다
 * ({@code ENDER_EYE_DEATH} = {@code entity/endereye/dead1,2}). <b>그런데 사람이 플레이해 보고
 * 「그냥 폭발음으로해줘」라고 해서 되돌렸다.</b> 지금 쓰는 것은
 * {@link SoundEvents#GENERIC_EXPLODE} 다.
 *
 * <p>⚠ <b>되돌아간 자리가 {@code DRAGON_FIREBALL_EXPLODE} 가 아닌 것이 중요하다.</b> 그 둘은
 * <b>같은 소리</b>지만 이름이 다르고, 거짓말하는 이름으로 돌아가면 다음 사람이 「드래곤
 * 소리인데 왜 폭발음이 나지」로 같은 자리를 다시 판다. <b>값은 되돌리되 이름은 솔직한 쪽을
 * 쓴다</b> — 위에 적어 둔 발견을 지우지 않는 이유도 같다.
 *
 * <p>26.3 의 {@code SoundEvents.GENERIC_EXPLODE} 는 {@code Holder.Reference} 라
 * {@code TrialWarning.playEach} 의 <b>{@code Holder} 형태</b>로 그대로 들어간다
 * ({@code ENDER_EYE_DEATH} 는 맨 {@code SoundEvent} 여서 레지스트리에 감싸는 쪽으로 갔었다).
 * {@code javap} 로 확인한 것이고, 판이 올라 모양이 바뀌면 컴파일이 먼저 깨진다.
 *
 * <p>⚠ <b>이름으로 고르지 말고 {@code sounds.json} 을 열어 볼 것.</b> 이 카드가 그 함정에
 * 한 번 걸렸다.
 *
 * <h2>⚠⚠ 이 카드는 <b>겹침 금지 목록 밖</b>이다 — 고리끼리 겹친다</h2>
 *
 * <p><b>이 저장소에서 「즉사 메커닉 0개」를 깨는 첫 번째 자리다.</b> 값을 만지기 전에 이 절을
 * 끝까지 읽을 것.
 *
 * <h3>어쩌다 이렇게 됐는가</h3>
 *
 * <ol>
 *   <li>사람이 지점을 두 배(60~90곳)로 올리라고 했다</li>
 *   <li>그대로 {@code TrialRisks.reserveSpots} 를 지나게 하니 <b>90 을 불러도 84곳밖에 안
 *       섰고</b>, 같은 목록을 쓰는 「낙뢰」가 <b>9.4곳 → 4.2곳으로 반토막</b> 났다. 그것을
 *       사람에게 알렸다</li>
 *   <li>사람이 <b>「서로 겹쳐도 되니까 내가 말한 숫자로 해 줘」</b>라고 했다</li>
 * </ol>
 *
 * <p>그래서 이 카드만 목록에서 빠졌다. <b>대가를 알고 고른 값</b>이지 이 파일이 고른 것이
 * 아니다. 좋은 쪽도 하나 있다 — 이 카드가 자리를 안 잡으므로 <b>「낙뢰」는 열 곳을 도로 다
 * 받는다</b>(4.2 → 평균 10.00 / 10. 20만 판에서 열 곳이 다 선 판이 100% 였다).
 *
 * <h3>⚠ 고리 셋이 겹친 자리는 무장하고도 전멸이다</h3>
 *
 * <p>피해 23 · {@code explosion(null, null)} 이라 다이아 풀셋 + 보호 IV 기준으로 이렇다
 * ({@code GearedDamage}).
 *
 * <table border="1">
 *   <caption>한 틱에 맞는 발 수</caption>
 *   <tr><th>발</th><th>실제 피해</th><th></th></tr>
 *   <tr><td>1</td><td>6.77</td><td>산다</td></tr>
 *   <tr><td>2</td><td>13.54</td><td>산다</td></tr>
 *   <tr><td><b>3</b></td><td><b>20.31</b></td><td><b>팀 체력 20 — 전멸</b></td></tr>
 * </table>
 *
 * <p>「낙뢰」와 겹치는 것도 이제 안 막힌다. <b>낙뢰(6.93) + 비(6.77) = 13.7 이라 둘은
 * 살지만</b>, 거기에 이 카드의 고리가 하나만 더 겹치면 <b>20.5 로 죽는다.</b>
 *
 * <h3>얼마나 자주 그런 자리가 생기는가 — 20만 판을 굴린 값</h3>
 *
 * <p>반경 40 아레나에 반경 2.5 짜리 90곳을 겹침 검사 없이 뿌린 경우다. 「가장 깊은 겹침」은
 * 배치의 모든 원-원 교점에서 덮은 원 수를 세어 구한 <b>정확값</b>이고, 넓이는 몬테카를로다.
 *
 * <table border="1">
 *   <caption>한 볼리의 가장 깊은 겹침(90곳 · 20만 판)</caption>
 *   <tr><th>깊이</th><th>그런 볼리의 비율</th></tr>
 *   <tr><td>2겹</td><td>0.005%</td></tr>
 *   <tr><td>3겹</td><td>28.24%</td></tr>
 *   <tr><td>4겹</td><td>59.34%</td></tr>
 *   <tr><td>5겹</td><td>11.35%</td></tr>
 *   <tr><td>6겹</td><td>1.00%</td></tr>
 *   <tr><td>7겹</td><td>0.064%</td></tr>
 *   <tr><td>8겹</td><td>0.005%</td></tr>
 *   <tr><td>9겹</td><td>0.001%</td></tr>
 * </table>
 *
 * <p>⚠ <b>3겹 이상 구역이 생기는 볼리가 99.995% 다.</b> 사실상 <b>모든 볼리에 즉사 구역이
 * 하나 이상 있다.</b> 4겹 이상도 71.8% 에 있다.
 *
 * <table border="1">
 *   <caption>그 구역이 아레나 넓이에서 차지하는 비율</caption>
 *   <tr><th>깊이</th><th>넓이 비율</th><th>반경 40 아레나에서</th></tr>
 *   <tr><td>1겹 이상</td><td>28.98%</td><td>1457칸²</td></tr>
 *   <tr><td>2겹 이상</td><td>4.67%</td><td>235칸²</td></tr>
 *   <tr><td><b>3겹 이상</b></td><td><b>0.513%</b></td><td><b>25.8칸²</b></td></tr>
 *   <tr><td>4겹 이상</td><td>0.043%</td><td>2.1칸²</td></tr>
 * </table>
 *
 * <p>읽는 법은 이렇다. <b>즉사 구역은 거의 언제나 있지만 아레나의 0.5% 다.</b> 아무 데나 서
 * 있다가 걸릴 확률이 한 볼리에 0.5% 이고, 그마저 <b>30틱 동안 고리 셋이 겹쳐 보이는 자리</b>라
 * 눈으로 알아볼 수 있다 — 고리가 <b>비키지 말아야 할 곳</b>이 아니라 <b>가장 비켜야 할 곳</b>을
 * 말한다는 점에서 예고는 여전히 정직하다.
 *
 * <p>그래도 <b>즉사는 즉사다.</b> 이 판의 원칙이 「즉사 메커닉 0개」였고 이 카드가 그것을
 * 깼다. 값을 되돌리는 것이 아니라 <b>알고 두는 것</b>이 지금의 상태다.
 *
 * <h3>지점은 이 파일이 직접 굴린다 — 겹침 검사만 빼고 나머지는 그대로다</h3>
 *
 * <p>{@link #rollSpot} 이 {@code TrialRisks.groundSpot} 과 <b>겹침 검사 한 줄만 다르다.</b>
 * 나머지는 일부러 같게 두었다.
 *
 * <ul>
 *   <li>{@link TrialRisks#arenaOffset} 을 그대로 쓴다 — 아레나 반경도 분포(원 안 고르게)도
 *       남의 카드와 같아야 한다</li>
 *   <li><b>허공을 뽑지 않는다.</b> 중앙 섬은 둥글지 않아 반경 40 안에도 빈 곳이 있고, 거기서
 *       터지면 예고도 피해도 뜻이 없다. 하이트맵이 월드 바닥을 돌려주면 다시 굴린다</li>
 *   <li><b>그 자리의 지표를 재서 담는다.</b> 구체가 제 자리의 땅에 닿으려면 {@code spot.y} 가
 *       실제 지표여야 한다({@link Volley} 의 설명)</li>
 *   <li>{@link TrialRisks#SPOT_TRIES} 번까지 다시 굴리고 포기한다 — 허공만 계속 뽑는 경우다</li>
 * </ul>
 *
 * <p>⚠ <b>{@code TrialRisks} 쪽은 한 줄도 안 고쳤다.</b> 겹침 금지는 「낙뢰」를 비롯한 다른
 * 카드에 여전히 필요하고, 거기를 헐겁게 하면 이 카드 하나 때문에 판 전체가 즉사가 된다.
 * <b>예외는 이 파일 안에서만 만든다.</b>
 *
 * <p>그래서 이 카드는 {@code reserveSpots} 도 {@code releaseSpots} 도 부르지 않고
 * {@code LIVE_SPOTS} 에 한 줄도 올리지 않는다. <b>남의 고리를 피하지도 않고 남이 우리를
 * 피하지도 않는다</b> — 한쪽만 새는 상태가 아니라 <b>양쪽 다 끈 상태</b>여야 한다. 한쪽만
 * 되돌리면 「낙뢰」가 다시 반토막 나면서 겹침은 그대로 남는다.
 *
 * <p>{@link #tick} 이 받는 {@code key} 는 이제 <b>겹침 목록이 아니라 {@link #RAINS} 의
 * 열쇠로만</b> 쓴다. 그래도 분배기에게 받는 것은 그대로다 — 열쇠를 두 곳에서 만들면 언젠가
 * 갈라진다.
 *
 * <h3>부른 만큼 다 선다</h3>
 *
 * <p>겹침 검사가 없으니 {@link TrialRisks#reserveSpots} 가 자리를 못 찾아 빠지는 일이
 * 없어졌다. 90 을 부르면 <b>90곳이 다 선다</b>(중앙 섬 위를 뽑는 한). 전에는 84.2곳이었다.
 *
 * <h2>예고 30틱은 「제자리에서 옆으로 비키기」의 하한이다</h2>
 *
 * <p>{@link TrialWarning#TICKS_SIDESTEP} 이 정확히 30틱이고 이 카드의 {@code warnTicks} 가 그
 * 값이다. 이 카드가 요구하는 행동이 딱 그것이기 때문이다 — <b>자리는 볼리가 열리는 순간
 * 얼어붙고</b>(굴린 좌표를 착탄까지 그대로 들고 간다) 사람을 따라오지
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
	 * ⚠⚠ 한 사람이 <b>한 틱에 맞을 수 있는 고리 수.</b> {@code TrialRisks.worstCaseTickDamage}
	 * 가 이 값을 곱한다.
	 *
	 * <h2>전에는 1 이었고, 그것은 <b>증명된 1</b> 이었다</h2>
	 *
	 * <p>{@code TrialRisks.reserveSpots} 가 고리끼리 두 반경의 합보다 멀게 떼어 놓았으므로
	 * 어느 자리에 서 있어도 고리 하나에만 들었다. <b>사람이 겹침을 허용하면서 그 증명이
	 * 사라졌다</b> — 까닭은 클래스 설명의 「겹침 금지 목록 밖이다」에 있다.
	 *
	 * <h2>지금 이 9 는 <b>증명이 아니라 실측</b>이다</h2>
	 *
	 * <p>반경 40 아레나에 반경 2.5 짜리 90곳을 겹침 검사 없이 뿌린 <b>20만 판</b>에서 실제로
	 * 나온 가장 깊은 겹침이다(1판, 0.001%). 흔한 쪽은 3~4겹이고 3겹 이상이 99.995% 의 볼리에
	 * 있다 — 분포는 클래스 설명의 표에 있다.
	 *
	 * <p>⚠ <b>이것은 천장이 아니다.</b> 무작위로 뿌리는 이상 구조적 상한이 없고, 더 굴리면 더
	 * 깊은 것이 나온다. 그래도 값을 적어 두는 것은 <b>{@code worstCaseTickDamage} 가 「1」이라고
	 * 거짓말하는 것을 막기 위해서</b>다. 이 값을 1 로 되돌리려면 겹침 금지를 먼저 되살릴 것.
	 *
	 * <p>⚠ <b>이 카드가 안전 시험의 유일한 예외다.</b> 무장 기준으로 3겹이면 20.31 이라 팀 체력
	 * 20 을 넘는다 — 「즉사 메커닉 0개」를 깬 첫 카드이고, {@code TrialRisksTest} 가 <b>다른
	 * 카드가 슬쩍 따라 나오지 못하게</b> 이 예외를 이름으로 붙들고 있다.
	 */
	static final int WORST_CASE_OVERLAP = 9;

	/**
	 * 이 카드가 <b>예고 한 틱에</b> 쓸 수 있는 점 수.
	 *
	 * <p>400 은 이 저장소가 쓰는 한 틱 예산이다 — 「낙뢰」가 반경 3 짜리 고리 열 개로 쓰는 값이고
	 * ({@code TrialEnderPulse.MAX_POINTS_PER_TICK} · {@code TrialEnderStorm.MAX_POINTS_PER_TICK}
	 * 도 같은 400 이다), 그 값을 여기서도 그대로 쓴다.
	 *
	 * <h2>고리를 걷어낸 자리가 전부 구체로 갔다</h2>
	 *
	 * <p><b>전에는 이름이 {@code MARK_BUDGET} 이었고 고리와 구체가 이 400 을 나눠 썼다.</b> 90곳
	 * 기준으로 고리가 270점 · 구체가 90점이었다 — 구체를 지점마다 두 점으로만 올려도 고리에 남는
	 * 몫이 220점으로 줄어 나눔이 상한에 걸려 <b>예산을 넘었다.</b> 곧 <b>고리가 있는 한 구체를
	 * 키울 길이 없었다.</b>
	 *
	 * <p>바닥 표식이 디스플레이 개체로 옮겨 가면서({@link TrialEndRainPanel}) 고리가 쓰던 270점이
	 * 통째로 비었고, 그래서 지점마다 네 점을 쓴다({@link #ORB_POINTS_PER_SPOT}) — 90곳이면
	 * <b>360점</b>이다. 이름을 {@code POINT_BUDGET} 으로 고친 것은 <b>이제 「표식의 예산」이 아니라
	 * 「구체의 예산」</b>이기 때문이다. 실제 값은 {@link #tickPoints} 가 돌려주고
	 * {@code TrialEndRainTest} 가 카드 값에서 직접 센다.
	 *
	 * <p>이 값은 <b>이 카드 혼자</b>의 몫이다. 시련은 전투가 끝날 때까지 쌓이므로 같은 틱에 남의
	 * 고리도 함께 그려진다는 것을 잊지 말 것.
	 */
	static final int POINT_BUDGET = 400;
	/**
	 * 착탄하는 <b>그 한 틱</b>의 점 예산.
	 *
	 * <p>예고 예산의 두 배를 준다. 착탄 틱에는 <b>구체도 면도 없기</b> 때문이다 — {@link #land} 가
	 * 볼리를 비우므로 {@link #warn} 이 그릴 것이 없고 면도 그 틱에 지워진다. 한 틱뿐이고 그 틱에
	 * 다른 몫이 없으니 두 배까지는 받아들인다.
	 *
	 * <p>이 값이 생기기 전에는 지점마다 25점(폭발 1 + {@code REVERSE_PORTAL} 24)을 쐈다. 45곳이면
	 * 이미 <b>1125점</b>이고 90곳이면 2250점이라, 예산이 없던 쪽이 훨씬 컸다.
	 */
	static final int IMPACT_BUDGET = POINT_BUDGET * 2;
	/**
	 * 고리 한 바퀴를 나눠 그릴 수 있는 <b>최대 틱 수</b>. ⚠ <b>이 카드는 이제 안 쓴다.</b>
	 *
	 * <p>고리는 {@link TrialWarning#dust} 가 만드는 먼지 파티클이고 그 수명이 <b>최소 8틱</b>이다
	 * (26.3 {@code DustParticleBase} 의 생성자 —
	 * {@code max(1, (int)(8.0 / (nextDouble() * 0.8 + 0.2)) * scale)}, 우리는 {@code scale} 이
	 * 1.0 이다). 한 바퀴를 8틱 이상에 걸쳐 그리면 마지막 점을 찍기 전에 첫 점이 죽어
	 * <b>고리가 영영 안 닫힌다.</b> 6 은 그 8 에서 두 틱을 뺀 자리다.
	 *
	 * <p>⚠⚠ <b>이 카드의 고리가 없어졌는데도 이 상수가 여기 남아 있는 까닭</b>은 <b>세 곳이 이
	 * 값을 가리키고 있기</b> 때문이다 — {@code DragonLastStandPatterns} 의
	 * {@code SUCK_MARK_STRIDE}·{@code CROSS_MARK_STRIDE}·{@code LIGHTNING_MARK_STRIDE} 가
	 * 「{@code TrialEndRain} 의 상한을 그대로 쓴다」고 적고 가져갔고, {@code TrialCrystalRevive} 도
	 * 설명에서 여기를 가리킨다. <b>지우면 그 세 곳이 컴파일되지 않는다.</b>
	 *
	 * <p>근거(먼지 수명 8틱)는 그 세 곳에서도 그대로 참이라 값이 거짓이 되지는 않았다. 다만
	 * <b>살 자리가 여기가 아니다</b> — 「왜 8인가」가 적힌 원본은 {@code TrialWarning.markGround}
	 * 이므로, 저 세 곳을 고칠 수 있는 사람이 그쪽으로 옮기는 것이 맞다.
	 */
	static final int MARK_MAX_STRIDE = 6;

	/**
	 * ⚠ <b>하늘에서 내려오는 것과 터지는 것의 색.</b> 구체의 알맹이·겉·착탄이 전부 여기서 나온다.
	 *
	 * <p><b>바닥 면은 이 색을 쓰지 않는다</b> — 그쪽은 색유리 블록이 들고 있는 보라다
	 * ({@link TrialEndRainPanel#GLASS_COLOR}). 색이 둘로 갈린 경위와 근거는 클래스 설명의
	 * 「색이 둘이다」에 있다. <b>한마디로 배경이 둘이라서다</b>: 바닥은 흰 엔드스톤이고 하늘은
	 * 어두운 보랏빛이다.
	 *
	 * <p><b>전에는 자홍({@code 0xC800C8})이었다.</b> 사람이 「이펙트도 보라색으로」라고 해서
	 * 그리로 갔다가, 플레이해 보고 <b>「보라색이아닌 빨간색원으로다시 복귀하자 이번건 너무
	 * 가시성이안좋아」</b>라고 해서 되돌렸다. 되돌린 이유는 <b>가시성 하나</b>다 — 규약이 아니라
	 * 눈이 정한 것이고, 마침 규약과도 맞는다.
	 *
	 * <p>돌아온 자리가 {@link TrialWarning.Colors#DEADLY}, 곧 규약의 <b>「서 있으면 죽는다」</b>
	 * 다. 이 카드가 정확히 그 뜻이라 빌려 쓰는 것이 아니라 <b>제자리로 온 것</b>이다. 처음 이
	 * 카드를 만들 때도 {@link TrialWarning#markGround} 를 그냥 불러 이 빨강을 썼다.
	 *
	 * <p>⚠ <b>상수 하나로 둔 까닭</b>이 있다. 구체의 알맹이(먼지)와 겉(별), 그리고 착탄이 서로
	 * 다른 색이면 <b>한 덩어리로 안 보인다.</b> 사람이 다르게 원하면 <b>여기 한 줄</b>만 고치면
	 * 된다 — 색을 세 군데에 나눠 적지 말 것.
	 *
	 * <p>⚠ <b>보라로 되돌리지 말 것.</b> 사람이 직접 물린 값이고, 바닥이 보라가 된 지금은 더욱
	 * 그렇다 — 둘을 같은 보라로 묶으면 구체가 엔드 하늘에 묻힌다.
	 */
	static final int MARK_COLOR = TrialWarning.Colors.DEADLY;
	/**
	 * 구체와 착탄 입자에 실어 보내는 <b>ARGB</b>.
	 *
	 * <p>⚠ {@code ColorParticleOption.create(타입, int)} 가 받는 것은 RGB 가 아니라 ARGB 이고
	 * ({@code ARGB.alpha/red/green/blue} 로 뜯는다), <b>최상위 바이트가 0 이면 완전 투명</b>이라
	 * 구체가 통째로 안 보인다. {@link #MARK_COLOR} 에 불투명을 얹는 자리가 여기다.
	 *
	 * <p>색 자체는 {@link #MARK_COLOR} 하나에서 온다 — 여기에 다른 색을 적지 말 것.
	 */
	static final int ORB_ARGB = 0xFF000000 | MARK_COLOR;
	/**
	 * 걷어낸 고리가 쓰던 점 간격(칸). <b>값이 아니라 자(尺)로 남긴다.</b>
	 *
	 * <p>고리는 {@code MARK_POINT_GAP = 0.9} 로 점 수를 뽑았고, 반경 2.5 의 둘레가 15.71칸이라
	 * 한 바퀴가 <b>18점 · 실제 간격 0.87칸</b>이었다. 그 0.87 이 지금도 쓸모가 있다 — <b>바닥
	 * 표식이 경계를 얼마나 정확히 말하는가</b>를 견주는 자가 그것이고, 면은
	 * {@link TrialEndRainPanel#edgeGap} 으로 <b>0.19칸</b>이라 네 배 또렷하다.
	 * {@code TrialEndRainTest} 가 그 비교를 숫자로 들고 있다.
	 *
	 * <p>고리를 되살릴 일이 있다면 {@link TrialWarning#POINT_GAP}(0.5)을 쓰지 않았던 까닭부터
	 * 볼 것 — 0.5 면 한 바퀴가 32점이고 90곳이면 한 틱에 540점이라 예산 밖이었다.
	 */
	static final double RETIRED_RING_GAP = 0.87;
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
	 * 구체의 <b>알맹이</b>에 쓰는 점 수(지점마다 · 매 틱). 먼지다.
	 *
	 * <p>중력이 없어 찍힌 자리에 그대로 서므로, 구체가 지나온 길이 <b>하늘에서 바닥까지 점선
	 * 기둥</b>으로 남는다. 바닥 면이 시선과 나란해 안 보이는 각도에서 「어디」를 대신 말하는 것이
	 * 이 두 점이다.
	 */
	static final int ORB_CORE_POINTS = 2;
	/**
	 * 구체의 <b>겉</b>에 쓰는 점 수(지점마다 · 매 틱). 별({@code ENTITY_EFFECT})이다.
	 *
	 * <p>중력이 {@code -0.1} 이라 천천히 떠올라 꼬리가 들린다. 알맹이가 남긴 기둥 위로 이것이
	 * 번져 「떨어지는 덩어리」가 된다.
	 */
	static final int ORB_HALO_POINTS = 2;
	/**
	 * 구체 하나가 <b>한 틱에</b> 쓰는 점 수. 알맹이 + 겉이다.
	 *
	 * <p><b>전에는 1 이었다.</b> 고리가 예산의 270점을 쓰고 있어 2 로만 올려도 한 틱 450점이 되어
	 * 넘쳤다 — 「구체 하나에 한 점」이 그때는 <b>유일한 값</b>이었다. 사람이 「떨어지는걸 좀더
	 * 보이게」라고 했고, 바닥 표식이 개체로 옮겨 가 그 270점이 비면서 <b>네 점</b>이 됐다
	 * (90곳이면 360점 · 예산 {@link #POINT_BUDGET} 400 안이다).
	 *
	 * <p>⚠ <b>5 로는 못 올린다.</b> 90곳 × 5 = 450점으로 예산을 넘는다. 더 보이게 해야 한다면
	 * 점을 늘리는 쪽이 아니라 <b>꼴</b>을 바꿀 것 — 크기는 못 키운다(클래스 설명의 「구체를 네
	 * 배로 키웠다」에 까닭이 있다).
	 */
	static final int ORB_POINTS_PER_SPOT = ORB_CORE_POINTS + ORB_HALO_POINTS;
	/**
	 * 알맹이를 흩뿌리는 폭(블록).
	 *
	 * <p>0 이면 점이 한 줄로 정확히 찍혀 <b>자로 그은 선</b>이 된다. 기둥으로 읽혀야 하므로 겉보다
	 * 좁게 둔다 — 넓히면 기둥이 흐려져 「어디」가 번진다.
	 */
	private static final double ORB_CORE_SPREAD = 0.10;
	/**
	 * 겉을 흩뿌리는 폭(블록). 가로 쪽이다.
	 *
	 * <p>전에 한 점짜리 구체가 쓰던 0.12 보다 넓다. 점이 둘이므로 조금 벌려야 <b>덩어리</b>로
	 * 보이고, 더 벌리면 구체가 아니라 흩뿌려진 먼지가 된다.
	 */
	private static final double ORB_HALO_SPREAD = 0.22;
	/**
	 * 겉을 <b>세로로</b> 흩뿌리는 폭(블록). 가로보다 넓다.
	 *
	 * <p>떨어지는 것은 세로로 길어야 「내려오는 중」으로 읽힌다. 틱당 낙하가 0.67칸이므로
	 * ({@link #ORB_DROP_HEIGHT} ÷ 예고 30틱) 0.45 는 <b>한 틱 거리의 3분의 2</b>라 틱 사이가 메워져
	 * 줄기가 이어지고, 그보다 크게 벌리면 <b>머리가 어디인지 흐려져</b> 「언제」가 무너진다 —
	 * 바닥에 닿는 순간을 읽는 것이 이 카드의 전부다.
	 */
	private static final double ORB_HALO_RISE = 0.45;
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
	 * <p>⚠ <b>좌표의 {@code y} 가 그 자리의 실제 지표다.</b> {@link #rollSpot} 이 자리를 굴릴 때
	 * 이미 {@code getHeightmapPos(MOTION_BLOCKING_NO_LEAVES, ...)} 로 그 칸의 설 수 있는 높이를
	 * 물어 담는다 — {@code TrialEnderPulse.Ground} 가 점마다 하는 계산과 같은 값이다. 그래서
	 * <b>구체가 떨어질 바닥을 여기서 다시 묻지 않는다</b>: 볼리가 열린 틱의 지표를 착탄까지
	 * 그대로 들고 가고, 자리마다 높이가 달라도 구체는 제 자리의 땅에 닿는다.
	 *
	 * @param spots   이번 볼리의 고리 중심들. {@link #rollSpots} 가 굴린 자리다
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
	 * <p>{@link #RAINS} 의 칸은 「끝났는가」가 아니라 <b>「아직 바닥에 떠 있는 볼리가
	 * 있는가」</b>를 들고 있다. 마지막 볼리가 터진 틱에 정확히 한 번 지워진다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). ⚠ <b>이 카드는 겹침 금지 목록을 쓰지 않으므로</b> 열쇠가 하는 일은
	 * {@link #RAINS} 의 칸을 가르는 것뿐이다. 그래도 <b>스스로 만들지 않고 분배기에게 받는다</b> —
	 * 두 곳에서 만든 열쇠는 언젠가 갈라지고, 값이 같은 위험을 카드 둘에 걸면 둘이 같은 칸을 쓴다.
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
			// 다른 판에서 받은 카드의 찌꺼기다. 버리고 처음부터 센다.
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
		// 방금 터진 자리의 면이 그 틱에 지워지고, 그리는 것이 마지막이라야 이번 틱에 열린 볼리가
		// 첫 틱부터 구체와 소리를 낸다(면은 볼리를 여는 그 틱에 깔린다).
		run = land(end, members, key, run, now, risk);
		if (raining) {
			run = openVolley(end, key, run, now, elapsed, risk);
		}
		warn(end, members, run, now, risk);

		if (run.volley() == null && !raining) {
			// 마지막 볼리까지 끝났다. 기록을 지운다 — 다음 틱부터는 위의 「끝난 쪽은 영영
			// 되돌아간다」로 빠진다.
			finish(key);
			return;
		}
		RAINS.put(key, run);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p><b>전에는 여기서 {@code TrialRisks.releaseSpots} 로 잡아 둔 지점을 놓았다.</b> 이 카드가
	 * 겹침 금지 목록에서 빠지면서(클래스 설명의 「겹침 금지 목록 밖이다」) 잡아 두는 자리가
	 * 아예 없어져 놓을 것도 없어졌다. <b>한쪽만 되돌리지 말 것</b> — 잡기를 되살리면 놓기도
	 * 함께 되살려야 하고, 놓기만 남기면 남의 자리를 지운다.
	 *
	 * <p>비울 것이 둘이다.
	 *
	 * <ul>
	 *   <li>{@link #RAINS} — 월드가 바뀌면 남은 좌표가 새 판에서 터진다</li>
	 *   <li>⚠⚠ <b>깔려 있는 바닥 면</b>({@link TrialEndRainPanel#dropAll()}) — 파티클과 달리
	 *       <b>개체는 저절로 사라지지 않는다.</b> 여기가 {@code SERVER_STOPPED} 에서도 불리는
	 *       자리이고(그때는 월드를 만질 수 없다) 그래서 그쪽이 개체를 직접 들고 있다. ⚠ <b>이 줄을
	 *       지우면 다음 판까지 보라 판이 깔려 있는다</b> — 심지가 결국 태우기는 하지만 그것은
	 *       바닥이지 배선이 아니다</li>
	 * </ul>
	 */
	public static void clearState() {
		RAINS.clear();
		TrialEndRainPanel.dropAll();
	}

	/**
	 * 비가 그칠 때까지 — 패턴 타이머 HUD({@link TrialTimers})가 읽는다. <b>상태를 하나도 쓰지
	 * 않는다.</b>
	 *
	 * <p>볼리마다의 착탄 시각은 {@link Downpour#nextVolleyAt} 에 정확히 들어 있지만 <b>그것을 줄로
	 * 띄우지 않는다.</b> 볼리 간격이 2~3초(굴림)이고 착탄 예고가 1.5초라 숫자가 2초마다 튀어
	 * 읽히지 않고, 볼리 하나하나는 이미 바닥의 보라 판이 말한다. 사람이 쓸 수 있는 값은 「이 비가 언제
	 * 그치는가」라서 비가 내리는 동안({@link #raining}, 30초)을 「진행 중」 줄로 보인다.
	 *
	 * @return 비가 내리지 않거나(받은 틱 · 이미 그침) 값이 잘못 적힌 카드면 {@code null}
	 */
	static @Nullable TrialTimers.Clock clock(long granted, long now,
			@Nullable TrialCatalog.Risk.EndRain risk) {
		if (risk == null || !usable(risk)) {
			return null;
		}
		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		if (!raining(elapsed, risk)) {
			return null;
		}
		return TrialTimers.Clock.active(risk.durationTicks() + 1L - elapsed, risk.durationTicks());
	}

	// ------------------------------------------------------------------ 볼리 한 번

	/**
	 * 착탄할 때가 됐으면 터뜨린다.
	 *
	 * <p>착탄하는 틱에는 구체를 다시 그리지 않는다 — 볼리를 여기서 비우므로 {@link #warn} 이 그릴
	 * 것이 없다. 같은 틱에 표식을 한 벌 더 보내면 방금 터진 자리가 한 틱 더 위험해 보이고,
	 * 「터진 자리는 즉시 안전」이 그 한 틱에서 먼저 깨진다 — 「연쇄 포격」이 같은 자리에서 같은
	 * 판단을 한다.
	 *
	 * <p>⚠⚠ <b>바닥 면을 지우는 첫째 자리가 여기다</b>({@link TrialEndRainPanel#drop}). 파티클이면
	 * 그리지 않는 것으로 끝나지만 <b>개체는 지워 달라고 말해야 사라진다.</b> 터진 뒤의 바닥은
	 * 안전하므로 한 틱도 남겨 두지 않는다 — {@code DragonLastStandPatterns.fireCone} 이 부채꼴 면을
	 * 터지는 틱에 지우는 것과 같은 규칙이다.
	 *
	 * <p><b>전에는 여기서 {@code TrialRisks.releaseSpots} 로 자리를 놓았다.</b> 이제 잡는 자리가
	 * 없어 놓을 것도 없다(클래스 설명의 「겹침 금지 목록 밖이다」).
	 *
	 * <p>소리는 <b>지점마다가 아니라 사람마다</b> 한 번씩 낸다({@link #impactSound}).
	 */
	private static Downpour land(ServerLevel end, List<ServerPlayer> members, String key,
			Downpour run, long now, TrialCatalog.Risk.EndRain risk) {
		Volley volley = run.volley();
		if (volley == null || now < volley.landsAt()) {
			return run;
		}
		// 면을 먼저 지운다. 터지는 연출이 올라가는 틱에 보라 면이 함께 떠 있으면 「아직 온다」로
		// 읽힌다.
		TrialEndRainPanel.drop(key);
		for (Vec3 spot : volley.spots()) {
			detonate(end, members, spot, risk);
		}
		impactSound(end, members);
		return run.without();
	}

	/**
	 * 새 볼리를 연다.
	 *
	 * <p>착탄이 30초 안에 들어오는 볼리만 연다. 예고가 끝나기 전에 카드가 끝나면 <b>그려 놓은
	 * 표식이 착탄 없이 사라지고</b>, 그것은 예고가 거짓말을 한 것이 된다 — 이 전투는 이미 보여 준
	 * 표식을 무르지 않는다.
	 *
	 * <p>⚠ <b>자리를 이 파일이 직접 굴린다</b>({@link #rollSpots}). 전에는
	 * {@code TrialRisks.reserveSpots} 를 지나 남의 고리를 피했는데, 사람이 「서로 겹쳐도 되니까
	 * 내가 말한 숫자로」라고 정해 그 길에서 빠졌다 — 까닭과 대가는 클래스 설명에 있다.
	 *
	 * <p>이제 <b>부른 만큼 다 선다.</b> 빈 목록이 돌아오는 것은 굴린 자리가 전부 허공일 때뿐인데
	 * 중앙 섬 위라 사실상 없다. 그래도 그 길을 남겨 둔 것은 <b>한 자리도 못 얻은 볼리를 매 틱
	 * 다시 열지 않기 위해서</b>다 — 이번 볼리는 통째로 건너뛰고 다음 볼리 시각은 그대로 굴린다.
	 *
	 * <p>⚠ <b>바닥 면을 깔는 자리가 여기 하나다</b>({@link TrialEndRainPanel#raise}). 고리는 매 틱
	 * 다시 그렸지만 개체는 <b>한 번 깔면 서 있는다</b> — 그래서 「매 틱 그리기」가 통째로 없어졌고,
	 * 덤으로 <b>볼리 도중에 들어온 사람에게도 보인다</b>(파티클은 그 틱에 멀리 있던 사람에게 영영
	 * 가지 않았다).
	 *
	 * <p>⚠ <b>자리를 굴린 뒤에 깔아야 한다.</b> 면이 덮는 반경과 터지는 반경이 같은 값
	 * ({@code risk.radius()})에서 나오는 것이 「보이는 자리 = 터지는 자리」의 전부다 — 여기에 다른
	 * 숫자를 적지 말 것.
	 */
	private static Downpour openVolley(ServerLevel end, String key, Downpour run, long now,
			long elapsed, TrialCatalog.Risk.EndRain risk) {
		if (run.volley() != null || now < run.nextVolleyAt() || !landsInWindow(elapsed, risk)) {
			return run;
		}
		RandomSource random = end.getRandom();
		List<Vec3> spots = rollSpots(end, random, rolledSpots(random, risk));
		long next = now + rolledInterval(random, risk);
		if (spots.isEmpty()) {
			return new Downpour(run.granted(), next, null);
		}
		TrialEndRainPanel.raise(end, key, spots, risk.radius(), risk.warnTicks());
		return new Downpour(run.granted(), next, new Volley(spots, now + risk.warnTicks()));
	}

	// ------------------------------------------------------------------ 자리 굴리기

	/**
	 * 이번 볼리의 자리들. <b>서로 겹쳐도 그대로 둔다.</b>
	 *
	 * <p>{@code TrialRisks.reserveSpots} 와 달리 <b>이미 뽑은 자리도, 남의 카드 고리도 보지
	 * 않는다.</b> 사람이 「서로 겹쳐도 되니까 내가 말한 숫자로 해 줘」라고 정한 것이 이 한 줄이고,
	 * 그 대가(3겹이면 무장하고도 전멸)는 클래스 설명의 표에 적어 두었다.
	 *
	 * <p>⚠ <b>여기에 겹침 검사를 도로 넣지 말 것.</b> 넣는 순간 90 이 84 가 되고 「낙뢰」가
	 * 반토막 나는 자리로 되돌아간다. 되돌리려면 {@code TrialRisks.reserveSpots} 를 쓰면 되고,
	 * 그때는 {@link #clearState} 와 {@link #land} 의 놓기도 함께 되살릴 것.
	 */
	private static List<Vec3> rollSpots(ServerLevel end, RandomSource random, int count) {
		List<Vec3> spots = new ArrayList<>(Math.max(0, count));
		for (int index = 0; index < count; index++) {
			Vec3 spot = rollSpot(end, random);
			if (spot != null) {
				spots.add(spot);
			}
		}
		return spots;
	}

	/**
	 * 아레나 안에서 발 디딜 수 있는 자리 하나. <b>겹침은 보지 않는다.</b>
	 *
	 * <p>{@code TrialRisks.groundSpot} 에서 <b>겹침 검사 한 줄만 뺀 것</b>이고 나머지는 일부러
	 * 같게 두었다. 분포도({@link TrialRisks#arenaOffset} — 원 안에 고르게), 아레나 반경도
	 * ({@code TrialRisks.ARENA_RADIUS}), 다시 굴리는 횟수도({@link TrialRisks#SPOT_TRIES})
	 * 남의 카드와 같아야 한다 — 숫자를 여기 따로 적으면 아레나가 바뀔 때 한쪽만 따라간다.
	 *
	 * <p><b>허공은 여전히 거른다.</b> 중앙 섬은 둥글지 않아 반경 40 안에도 빈 곳이 있고, 거기서
	 * 터지면 예고도 피해도 뜻이 없다. 하이트맵이 월드 바닥을 돌려주면 그 자리다.
	 *
	 * <p><b>담는 {@code y} 는 그 칸의 지표다.</b> 구체가 제 자리의 땅에 닿는 근거가 이것이라
	 * ({@link Volley} 의 설명) 착탄까지 그대로 들고 간다.
	 *
	 * @return 끝내 허공만 뽑으면 {@code null}. 그 자리는 이번 볼리에서 빠진다
	 */
	private static @Nullable Vec3 rollSpot(ServerLevel end, RandomSource random) {
		for (int attempt = 0; attempt < TrialRisks.SPOT_TRIES; attempt++) {
			Vec3 offset = TrialRisks.arenaOffset(random.nextDouble(), random.nextDouble(),
					TrialRisks.ARENA_RADIUS);
			BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					BlockPos.containing(offset.x, 0.0, offset.z));
			// 허공이면 하이트맵이 월드 바닥을 돌려준다.
			if (ground.getY() > end.getMinY()) {
				return new Vec3(offset.x, ground.getY(), offset.z);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 구체를 한 칸 떨어뜨리고 경고음을 올린다.
	 *
	 * <h2>바닥은 여기서 그리지 않는다</h2>
	 *
	 * <p><b>전에는 이 함수가 매 틱 고리를 나눠 그렸다.</b> 점 하나가 패킷 한 장이고 예고 30틱
	 * 내내 나가서, 90곳의 고리를 한 틱에 다 그릴 수 없어 {@code markStride} 틱에 걸쳐 채우고
	 * 위상을 {@code now} 에서 뽑는 장치가 여기 있었다. <b>바닥 표식이 디스플레이 개체로 옮겨 가면서
	 * 그 전부가 없어졌다</b> — 면은 {@link #openVolley} 가 한 번 깔면 서 있는다
	 * ({@link TrialEndRainPanel}).
	 *
	 * <p>그래서 이 함수에 남은 것은 <b>매 틱 자리가 바뀌는 것</b>뿐이다.
	 *
	 * <h2>구체는 나눠 그리지 않는다</h2>
	 *
	 * <p>고리는 <b>같은 자리에 머무는 모양</b>이라 여러 틱에 나눠 채워도 완성됐다. 구체는
	 * <b>매 틱 자리가 바뀌므로</b> 건너뛴 틱은 채워지지 않고 그냥 빈다 — 나누면 구체가 깜박인다.
	 * 그래서 지점마다 매 틱 {@link #ORB_POINTS_PER_SPOT} 점을 그대로 쓴다. 지금은 예산 전부가
	 * 구체 몫이라 나눌 이유도 없다({@link #POINT_BUDGET}).
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
		// 입자는 고리 밖에서 한 번만 만든다. 지점 수만큼 새로 만들 이유가 없다.
		ParticleOptions core = coreParticle();
		ParticleOptions halo = orbParticle();
		for (Vec3 spot : volley.spots()) {
			dropOrb(end, spot, core, halo, remaining, risk);
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
	 * 하늘에서 떨어지는 구체를 이번 틱 자리에 찍는다. <b>두 벌로 나눠 보낸다.</b>
	 *
	 * <p>높이가 <b>남은 틱에 정비례</b>하므로({@link #orbHeight}) 구체가 지표에 닿는 틱과
	 * 착탄 틱이 어긋날 수 없다. 가속을 넣지 않은 것은 일부러다 — 실제 중력으로 떨어뜨리면 예고
	 * 대부분을 하늘 높이에서 보내다가 마지막 몇 틱에 쏟아지듯 내려와, <b>구체를 보고 남은
	 * 시간을 읽을 수 없다.</b> 선형이면 「절반 내려왔으니 절반 남았다」가 그대로 읽힌다.
	 * {@code TrialFireball.flightProgress} 와 {@code DragonFireBarrage} 도 같은 이유로 선형이다.
	 * <b>사람이 정한 설계라 여기에 가속을 넣지 말 것.</b>
	 *
	 * <p>바닥은 {@code spot.y} 다. {@link #rollSpot} 이 자리를 굴릴 때 이미 그 칸의 지표를 물어
	 * 담으므로, <b>자리마다 바닥 높이가 달라도 구체는 제 자리의 땅에 닿는다.</b> 여기서
	 * 하이트맵을 다시 두드리지 않는 이유이기도 하다 — 매 틱 지점 수만큼 묻게 된다.
	 *
	 * <p>{@code GROUND_OFFSET} 을 더해 바닥 면과 같은 높이에서 끝난다. 마지막으로 그려지는 틱은
	 * {@code remaining == 1} 이므로 구체는 지표 위 0.8칸쯤에서 마지막으로 보이고, 다음 틱에
	 * 터진다.
	 *
	 * <h2>⚠ 두 번 보내는 까닭 — 흩뿌리는 폭이 축마다 달라야 한다</h2>
	 *
	 * <p>한 번에 보내면 가로와 세로가 같은 폭으로 흩어져 <b>공</b>이 된다. 겉은 세로로 길어야
	 * 「내려오는 중」으로 읽히고({@link #ORB_HALO_RISE}) 알맹이는 좁아야 기둥이 또렷하므로
	 * ({@link #ORB_CORE_SPREAD}) 폭이 셋 다 다르다 — {@code sendParticles} 가 축마다 폭을 따로
	 * 받으므로 입자 종류가 둘인 것만으로 호출이 둘이 된다.
	 *
	 * <p>패킷은 지점마다 <b>두 장</b>이다. 고리 시절에는 지점마다 고리 3장 + 구체 1장으로 <b>네
	 * 장</b>이었으니 절반으로 줄었다 — 점은 네 배가 됐는데 패킷은 반이다({@code count} 를 올리는
	 * 것은 패킷 한 장 안에서 일어난다).
	 *
	 * <p><b>긴 형태</b>다. 짧은 형태로 되돌리면 32칸 밖 구체가 통째로 사라져, 아레나 반대편에서
	 * 보면 하늘은 비어 있는데 바닥만 터진다.
	 */
	private static void dropOrb(ServerLevel end, Vec3 spot, ParticleOptions core,
			ParticleOptions halo, int remaining, TrialCatalog.Risk.EndRain risk) {
		double height = orbHeight(remaining, risk.warnTicks());
		double y = spot.y + GROUND_OFFSET + height;
		// 알맹이 — 먼지다. 중력이 없어 제자리에 서므로 지나온 길이 점선 기둥으로 남는다.
		end.sendParticles(core, true, false, spot.x, y, spot.z,
				ORB_CORE_POINTS, ORB_CORE_SPREAD, ORB_CORE_SPREAD, ORB_CORE_SPREAD, 0.0);
		// 겉 — 별이다. 세로로 길게 흩어 떨어지는 줄기로 보이게 한다.
		end.sendParticles(halo, true, false, spot.x, y, spot.z,
				ORB_HALO_POINTS, ORB_HALO_SPREAD, ORB_HALO_RISE, ORB_HALO_SPREAD, 0.0);
	}

	/**
	 * 구체의 <b>겉</b>과 착탄에 쓰는 입자. 색은 {@link #MARK_COLOR} 에서 온다.
	 *
	 * <p><b>전에는 {@code WITCH} 였다.</b> 같은 {@code SpellParticle} 을 만들지만 26.3 의
	 * {@code WitchProvider} 가 <b>제 색을 자홍으로 직접 칠해</b> 서버가 무엇을 보내든 자홍이다.
	 * 사람이 빨강으로 되돌리라고 해서 <b>색을 우리가 정할 수 있는 타입</b>으로 갈았다 — 까닭은
	 * 클래스 설명의 「구체는 빨강이라 입자를 갈아야 했다」에 있다.
	 *
	 * <p>{@code ENTITY_EFFECT} 는 {@code SpellParticle.MobEffectProvider} 로 가고 그쪽이
	 * {@code ColorParticleOption} 의 ARGB 를 그대로 {@code setColor}·{@code setAlpha} 한다.
	 * <b>만들어지는 입자 클래스가 {@code WITCH} 와 같아</b> 재질도 중력({@code -0.1})도 수명
	 * (8~40틱)도 그대로다.
	 *
	 * <p>⚠ <b>{@link #ORB_ARGB} 를 {@link #MARK_COLOR} 로 바꿔 넣지 말 것.</b> 알파가 0 이 되어
	 * 구체가 통째로 안 보인다.
	 *
	 * <p>매 틱 지점 수만큼 만들지 않도록 <b>부르는 쪽이 한 번만 만들어 돌려 쓴다</b>.
	 */
	static ParticleOptions orbParticle() {
		return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, ORB_ARGB);
	}

	/**
	 * 구체의 <b>알맹이</b>에 쓰는 입자. 같은 빨강의 먼지다.
	 *
	 * <p>{@link TrialWarning#dust} 를 그대로 쓴다 — {@code scale} 이 1.0 이라 수명이 8~40틱이고,
	 * ⚠ <b>그 {@code scale} 을 올리면 수명도 함께 곱해져</b> 터진 뒤 하늘에 자국이 남는다(클래스
	 * 설명의 「입자 수명」).
	 *
	 * <p>고른 이유는 <b>중력이 없다는 것</b> 하나다. 찍힌 자리에 그대로 서므로 구체가 내려온 길이
	 * <b>하늘에서 바닥까지 점선 기둥</b>으로 남고, 그것이 바닥 면을 못 보는 각도에서 「어디」를
	 * 말한다. 걷어낸 고리가 쓰던 것과 <b>같은 입자</b>라 새로 들여온 것이 아니다.
	 */
	static ParticleOptions coreParticle() {
		return TrialWarning.dust(MARK_COLOR);
	}

	// ------------------------------------------------------------------ 착탄

	/**
	 * 고리 하나가 터진다. 블록은 건드리지 않고 불도 붙이지 않는다.
	 *
	 * <p><b>팀에게 한 번만</b> 들어간다. 안에 선 사람을 모두 때리면 넷이 모여 있을 때 네 배가 되어
	 * 팀 체력 20 을 훨씬 넘긴다 — 함께 움직이는 것이 공유 체력 게임의 올바른 대응인데 그것이
	 * 전멸이 된다. 「연쇄 포격」이 같은 이유로 같은 모양을 쓴다.
	 *
	 * <p>⚠ <b>고리끼리는 이제 겹친다.</b> 전에는 {@code TrialRisks.reserveSpots} 가 떼어 놓아
	 * 「한 사람이 한 볼리에 한 발」이었고 {@code worstCaseTickDamage} 가 그 근거로 「피해 × 1」을
	 * 셌는데, 사람이 겹침을 허용하면서 그것이 {@link #WORST_CASE_OVERLAP} 으로 바뀌었다.
	 * <b>겹친 고리 셋에 선 사람은 이 함수가 세 번 불려 세 발을 맞고, 무장하고도 20.31 이라
	 * 전멸이다</b> — 분포와 넓이는 클래스 설명의 표에 있다.
	 *
	 * <p>팀원 목록을 직접 돈다. 상자로 후보를 추릴 이유가 없다 — 어차피 한 명만 세고, 팀이 아닌
	 * 사람(관전자·다른 판의 누구)을 때릴 일도 없어야 한다.
	 *
	 * <p>피해원에 실체를 달지 않는다. 엔드 섬 가장자리에서 밀리면 대응 불가 즉사이고 공유 체력이라
	 * 한 사람의 낙사가 팀 전체를 끝낸다. 폭발 피해형을 쓰므로 폭발 보호는 그대로 듣는다 —
	 * 대비한 사람이 손해 보지 않아야 한다.
	 *
	 * <h2>연출이 한 색 한 겹뿐이다</h2>
	 *
	 * <p>{@code EXPLOSION}(회백색 덩어리)을 걷어냈다. 사람이 「이펙트도 보라색으로」라고 했는데
	 * 그 입자가 정확히 「TNT 처럼 보인다」의 원인이고, 위에 색을 한 겹 얹어 가리던 것이
	 * 이전 구현이다. 가리지 말고 <b>제 색만 남기는</b> 쪽으로 갔다. <b>색이 자홍에서 빨강으로
	 * 되돌아간 지금도 그 판단은 그대로다</b> — 착탄도 {@link #MARK_COLOR} 를 쓴다.
	 *
	 * <p>덮어씌우던 {@code REVERSE_PORTAL} 도 함께 걷어냈다 — 수명이 <b>60~61틱</b>이라 터진 뒤
	 * 3초 동안 자국이 남았고, 볼리 간격이 40~60틱이라 <b>그 자국이 다음 볼리의 고리와 겹쳤다.</b>
	 * 이미 안전한 자리가 위험해 보이는 것이 이 전투가 가장 피하는 종류의 거짓말이다.
	 */
	private static void detonate(ServerLevel end, List<ServerPlayer> members, Vec3 at,
			TrialCatalog.Risk.EndRain risk) {
		// 착탄 연출도 긴 형태로 보낸다. 맞는 사람은 어차피 가깝지만 나머지 셋이 「저기 떨어졌다,
		// 피했구나」를 봐야 예고가 완결된다. 아레나 반경 40 이면 흩어진 팀원은 쉽게 32칸을 넘는다.
		end.sendParticles(orbParticle(), true, false, at.x, at.y + 0.3, at.z,
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
	 * <p>소리는 {@link SoundEvents#GENERIC_EXPLODE}, <b>그냥 폭발음</b>이다. 사람이 「그냥
	 * 폭발음으로해줘」라고 해서 {@code ENDER_EYE_DEATH} 에서 되돌린 것이고, <b>이름이 솔직한
	 * 쪽</b>을 고른 까닭은 클래스 설명의 「소리」에 적어 두었다 —
	 * {@code DRAGON_FIREBALL_EXPLODE} 는 같은 소리인데 이름만 드래곤이다.
	 *
	 * <p>음높이를 0.7 로 내린다. 수십 곳이 한꺼번에 터지는 순간이라 낮을수록 무게가 맞는다.
	 * <b>이 값은 소리를 바꾸면서도 그대로 두었다</b> — 낮춰 놓은 것이 「한 발」이 아니라
	 * 「한꺼번에」를 말하기 위해서라 소리가 무엇이든 같다.
	 */
	private static void impactSound(ServerLevel end, List<ServerPlayer> members) {
		TrialWarning.playEach(end, members, SoundEvents.GENERIC_EXPLODE, 1.0F, 0.7F);
	}

	/**
	 * 기록을 지운다.
	 *
	 * <p><b>전에는 여기서 {@code TrialRisks.releaseSpots} 도 불렀다.</b> 이 카드가 겹침 금지
	 * 목록에서 빠지면서 놓을 자리가 없어졌다(클래스 설명의 「겹침 금지 목록 밖이다」).
	 *
	 * <p>⚠ <b>바닥 면도 함께 지운다.</b> 불리는 자리가 둘인데 <b>두 자리 다 면이 남을 수 있다.</b>
	 *
	 * <ul>
	 *   <li><b>다른 판에서 받은 카드의 찌꺼기를 버릴 때</b>({@link #tick}) — 그 판의 면이 아직 깔려
	 *       있다. 월드가 그대로인데 세션만 바뀐 길이라 {@link #clearState} 가 지나가지 않았다</li>
	 *   <li><b>30초가 다 끝났을 때</b> — 보통은 마지막 볼리가 터지면서 {@link #land} 가 이미
	 *       지웠지만, 볼리가 열린 채로 끝나는 길({@code spots} 가 비어 돌아온 뒤 등)이 남아 있다</li>
	 * </ul>
	 */
	private static void finish(String key) {
		RAINS.remove(key);
		TrialEndRainPanel.drop(key);
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
	 * 떨어지는 구체가 <b>한 틱에</b> 쓰는 점 수. 지점 수 × {@link #ORB_POINTS_PER_SPOT} 다.
	 *
	 * <p>구체는 나눠 그릴 수 없으므로({@link #warn} 의 「구체는 나눠 그리지 않는다」) 이 몫이
	 * <b>고정 비용</b>이다. 바닥이 점을 안 쓰게 된 지금은 이것이 <b>이 카드가 쓰는 점 전부</b>이고,
	 * 그래서 {@link #tickPoints} 가 이 값을 그대로 돌려준다.
	 */
	static int orbPoints(TrialCatalog.Risk.EndRain risk) {
		return Math.max(0, risk.maxSpots()) * Math.max(0, ORB_POINTS_PER_SPOT);
	}

	/**
	 * <b>예고 한 틱에</b> 이 카드가 쓰는 점 수 전부.
	 *
	 * <p>「상한 안인가」를 숫자로 물을 수 있는 값이라 값에서 직접 뽑는다 — 지점 수나 반경을 고치는
	 * 사람은 피해만 보고 패킷은 보지 않는다.
	 *
	 * <p><b>이제 구체 몫이 전부다.</b> 바닥 표식이 디스플레이 개체라 점을 한 개도 안 쓴다
	 * ({@link TrialEndRainPanel}) — 전에는 여기에 고리 몫({@code markPoints})이 더해졌고 그 둘을
	 * 따로 세면 합이 예산을 넘는 것을 아무도 못 보았다. 지금 값으로 세면 90곳 × 4점 =
	 * <b>360점</b>이고 예산은 400 이다.
	 *
	 * <p><b>볼리는 언제나 하나뿐</b>이라 이 값이 그대로 최대다. {@code minInterval}(40)이
	 * {@code warnTicks}(30)보다 커서 앞 볼리가 터진 뒤에야 다음 볼리가 열리고,
	 * {@code TrialRisksTest} 가 그 부등식을 카드 값에서 지킨다.
	 */
	static int tickPoints(TrialCatalog.Risk.EndRain risk) {
		return orbPoints(risk);
	}

	/**
	 * 한 볼리에 세우는 <b>바닥 면 개체 수</b>. 지점 수 × 판 수다.
	 *
	 * <p>점이 아니라 <b>개체</b>를 세는 값이다. 점 예산({@link #POINT_BUDGET})과 나란히 두는
	 * 까닭은 지점 수를 올리는 사람이 <b>늘어나는 것이 점이 아니라 개체</b>라는 것을 보게 하기
	 * 위해서다 — 상한은 {@link TrialEndRainPanel#PANEL_BUDGET} 이고 거기에 근거가 적혀 있다.
	 */
	static int panelCount(TrialCatalog.Risk.EndRain risk) {
		return Math.max(0, risk.maxSpots()) * TrialEndRainPanel.slabCount();
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
}

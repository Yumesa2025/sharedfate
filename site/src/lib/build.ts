/**
 * 이번 빌드를 알아보는 표식. 배포할 때는 커밋 해시, 손으로 빌드하면 빌드한 시각이다.
 *
 * GitHub Pages 는 HTML 을 10분(max-age=600) 동안 브라우저와 CDN 에 묵혀 두고, 그 헤더는
 * 우리가 바꿀 수 없다. 그래서 페이지가 이 값을 품고, 열릴 때 /build.json 의 값과 견줘
 * 다르면 새 판으로 한 번 다시 연다 (Base.astro).
 */
export const BUILD_ID = (process.env.GITHUB_SHA ?? '').slice(0, 12) || String(Date.now());

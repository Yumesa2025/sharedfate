import { BUILD_ID } from '../lib/build';

/** 지금 배포된 빌드의 표식. 페이지가 묵은 판인지 알아보는 데 쓴다. */
export function GET() {
  return new Response(JSON.stringify({ id: BUILD_ID }), {
    headers: { 'Content-Type': 'application/json' },
  });
}

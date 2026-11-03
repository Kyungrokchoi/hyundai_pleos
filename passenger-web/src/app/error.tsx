"use client";
import { Frame } from "@/components/Frame";
import { Notice } from "@/components/Notice";

export default function GlobalError({ reset }: { error: Error; reset: () => void }) {
  return (
    <Frame>
      <div className="stack">
        <Notice tone="alert" title="화면을 그리는 중에 문제가 생겼어요">잠시 뒤 다시 시도해 주세요. 입력한 정산 기록은 서버에 안전하게 남아 있어요.</Notice>
        <button className="btn" type="button" onClick={reset}>다시 시도</button>
      </div>
    </Frame>
  );
}

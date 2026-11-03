import Link from "next/link";
import { Empty } from "@/components/StateViews";
import { Frame } from "@/components/Frame";

export default function NotFound() {
  return (
    <Frame>
      <Empty title="찾는 화면이 없어요">
        <p>주소가 올바르지 않아요. 차량 화면의 QR로 다시 들어와 주세요.</p>
        <p style={{ marginTop: "var(--s-4)" }}><Link href="/">처음 화면으로</Link></p>
      </Empty>
    </Frame>
  );
}

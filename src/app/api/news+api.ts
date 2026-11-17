import { respond } from "@/server/respond";
import { boletines } from "@/server/boletines";

export function GET() {
  return respond(async () => ({ bulletins: await boletines() }), 600);
}

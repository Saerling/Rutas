import { respond } from "@/server/respond";
import { traffic } from "@/server/transit";

export function GET() {
  return respond(traffic, 20);
}

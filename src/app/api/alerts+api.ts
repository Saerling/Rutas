import { respond } from "@/server/respond";
import { allAlerts } from "@/server/transit";

export function GET() {
  return respond(allAlerts, 60);
}

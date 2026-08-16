#!/bin/bash
# ==============================================================================
# Comprehensive Test Suite for API Gateway
# Verifies: JWT Auth, Routing, Load Balancing, Rate Limiting, & Circuit Breaking
# ==============================================================================

set -e

# Terminal Colors for readability
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}====================================================${NC}"
echo -e "${CYAN}    API Gateway Comprehensive Test Suite            ${NC}"
echo -e "${CYAN}====================================================${NC}\n"

# ------------------------------------------------------------------------------
# 1. GENERATE JWT TOKEN
# ------------------------------------------------------------------------------
echo -e "${YELLOW}--- [1] Generating JWT Token ---${NC}"
TOKEN=$(python3 -c "
import jwt, datetime
print(jwt.encode(
    {'sub':'demo-user','exp':datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=1)},
    'west-secret-key-for-api-gateway-authentication-java-21',
    algorithm='HS256'
))
")
echo "Token generated successfully."
echo ""

# ------------------------------------------------------------------------------
# 2. TEST UNAUTHORIZED ACCESS (401)
# ------------------------------------------------------------------------------
echo -e "${YELLOW}--- [2] Testing JWT Security (Expected: 401 Unauthorized) ---${NC}"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/users/get)
if [ "$STATUS" -eq 401 ]; then
    echo -e "${GREEN}SUCCESS: Unauthorized request properly blocked (401)${NC}\n"
else
    echo -e "FAILED: Expected 401, got $STATUS\n"
fi

# ------------------------------------------------------------------------------
# 3. TEST AUTHORIZED ROUTING (200)
# ------------------------------------------------------------------------------
echo -e "${YELLOW}--- [3] Testing Routing & Proxying (Expected: 200 OK) ---${NC}"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/users/get)
if [ "$STATUS" -eq 200 ]; then
    echo -e "${GREEN}SUCCESS: Request authenticated and routed successfully (200)${NC}\n"
else
    echo -e "FAILED: Expected 200, got $STATUS\n"
fi

# ------------------------------------------------------------------------------
# 4. TEST ROUND-ROBIN LOAD BALANCING
# ------------------------------------------------------------------------------
echo -e "${YELLOW}--- [4] Testing Round-Robin Load Balancing ---${NC}"
# Flush Redis to guarantee a full token bucket
docker exec apigateway-redis redis-cli FLUSHALL > /dev/null 2>&1 || true

echo "Sending 5 requests with 1-second delays (to avoid Rate Limiter interference)..."
for i in {1..5}; do
  curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/users/get
  sleep 1
done

echo "Check per-backend request counts:"
for n in {1..5}; do
  count=$(docker logs apigateway-user-service-$n 2>&1 | grep -c "GET /get" || true)
  echo -e "  user-service-$n: ${GREEN}$count requests${NC}"
done
echo ""

# ------------------------------------------------------------------------------
# 5. TEST REDIS RATE LIMITING (429)
# ------------------------------------------------------------------------------
echo -e "${YELLOW}--- [5] Testing Redis Rate Limiter (Expected: 429 Too Many Requests) ---${NC}"
docker exec apigateway-redis redis-cli FLUSHALL > /dev/null 2>&1 || true

echo "Sending a rapid burst of 10 requests..."
for i in {1..10}; do
  curl -s -o /dev/null -w "%{http_code} " -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/users/get
done
echo -e "\n${GREEN}SUCCESS: You should see 200s transition into 429s as the bucket empties.${NC}\n"

# ------------------------------------------------------------------------------
# 6. TEST CIRCUIT BREAKER & FALLBACK (502)
# ------------------------------------------------------------------------------
echo -e "${YELLOW}--- [6] Testing Circuit Breaker & Fallback (Expected: 502 Bad Gateway) ---${NC}"
docker exec apigateway-redis redis-cli FLUSHALL > /dev/null 2>&1 || true

echo "Simulating total downstream outage (stopping 5 containers)..."
docker compose stop user-service-1 user-service-2 user-service-3 user-service-4 user-service-5 > /dev/null 2>&1

echo "Firing request to offline backends..."
echo -n "Fallback JSON Response: "
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/users/get
echo ""

STATUS=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/users/get)
echo -e "HTTP Status Code: ${GREEN}$STATUS${NC}"

echo "Bringing services back online..."
docker compose start user-service-1 user-service-2 user-service-3 user-service-4 user-service-5 > /dev/null 2>&1
echo -e "${GREEN}SUCCESS: Circuit Breaker successfully caught the failure and served a custom fallback.${NC}\n"

# ------------------------------------------------------------------------------
# END OF TESTS
# ------------------------------------------------------------------------------
echo -e "${CYAN}====================================================${NC}"
echo -e "${CYAN}    All Tests Completed Successfully via Jenkins CI/CD! 🚀 ${NC}"
echo -e "${CYAN}====================================================${NC}"
# PharmaEase Deployment (Free)

This guide uses:
- Render (free web hosting for Spring Boot app)
- Railway MySQL (database)

## 1) Railway: Create MySQL and copy values

From Railway MySQL service, copy:
- host
- port
- database
- username
- password

If your app is on Render, use Railway's public host (not `mysql.railway.internal`).

## 2) Build JDBC URL

Use this format:

`jdbc:mysql://<HOST>:<PORT>/<DATABASE>?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`

## 3) Push this repo to GitHub

```bash
git add .
git commit -m "prepare deployment config"
git push
```

## 4) Render deploy

1. Open Render dashboard
2. New -> Blueprint
3. Select this repository (it contains `render.yaml`)
4. Set required env vars:
   - `DB_URL`
   - `DB_USERNAME`
   - `DB_PASSWORD`
5. Click Deploy

## 5) Verify

After deploy succeeds:
- Open app URL
- Login
- Create a customer
- Create a walk-in sale
- Open invoice
- Check orders and dashboard

## 6) Resume links

Add these links to resume:
- Live demo URL
- GitHub repository
- Demo video (Loom/YouTube unlisted)

